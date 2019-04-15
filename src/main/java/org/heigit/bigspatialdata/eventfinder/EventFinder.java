package org.heigit.bigspatialdata.eventfinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.math3.fitting.WeightedObservedPoint;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDBDatabase;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDBH2;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDBIgnite;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDBJdbc;
import org.heigit.bigspatialdata.oshdb.api.generic.OSHDBCombinedIndex;
import org.heigit.bigspatialdata.oshdb.api.mapreducer.OSMContributionView;
import org.heigit.bigspatialdata.oshdb.api.object.OSMContribution;
import org.heigit.bigspatialdata.oshdb.osm.OSMType;
import org.heigit.bigspatialdata.oshdb.util.OSHDBBoundingBox;
import org.heigit.bigspatialdata.oshdb.util.OSHDBTimestamp;
import org.heigit.bigspatialdata.oshdb.util.time.OSHDBTimestamps;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.LoggerFactory;
import org.wololo.geojson.Feature;
import org.wololo.geojson.FeatureCollection;
import org.wololo.geojson.GeoJSONFactory;
import org.wololo.jts2geojson.GeoJSONReader;

public class EventFinder {

  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(EventFinder.class);

  public static void main(String[] args) throws Exception {

    LOG.info("Start preparation");

    String rootPath = Thread.currentThread().getContextClassLoader().getResource("").getPath();
    String propertiesPath = rootPath + "oshdb.properties";

    Properties oshdbProperties = new Properties();
    oshdbProperties.load(new FileInputStream(propertiesPath));

    OSHDBDatabase oshdb;
    OSHDBJdbc keytables;
    if (oshdbProperties.getProperty("type").contains("H2")) {
      oshdb = (new OSHDBH2(oshdbProperties.getProperty("oshdb")))
          .multithreading(true)
          .inMemory(false);
      keytables = (OSHDBJdbc) oshdb;
    } else {
      oshdb = new OSHDBIgnite(EventFinder.class.getResource("/ignite-prod-ohsome-client.xml")
          .getFile());
      oshdb.prefix("global_v4");
      Connection conn = DriverManager.getConnection(
          "jdbc:postgresql://10.11.12.21:5432/keytables-global_v4", "ohsome", args[0]);
      keytables = new OSHDBJdbc(conn);
    }

    String[] split = oshdbProperties.getProperty("bbox").split(",");

    OSHDBBoundingBox bb = new OSHDBBoundingBox(
        Double.valueOf(split[0]),
        Double.valueOf(split[1]),
        Double.valueOf(split[2]),
        Double.valueOf(split[3]));

    Map<Integer, Polygon> polygons = EventFinder.getPolygons();

    SortedMap<OSHDBCombinedIndex<Integer, OSHDBTimestamp>, MappingMonth> queryDatabase
        = EventFinder.queryDatabase(bb, oshdb, keytables, polygons);

    Map<Integer, ArrayList<MappingEvent>> events = EventFinder
        .extractEvents(queryDatabase, oshdb, keytables, polygons);
    oshdb.close();

    EventFinder.writeOutput(events);

  }

  public static SortedMap<OSHDBCombinedIndex<Integer, OSHDBTimestamp>, MappingMonth> queryDatabase(
      OSHDBBoundingBox bb,
      OSHDBDatabase oshdb,
      OSHDBJdbc keytables,
      Map<Integer, Polygon> polygons)
      throws IOException, Exception {

    LOG.info("Run Query");

    StopWatch createStarted = StopWatch.createStarted();
    // collect contributions by month
    SortedMap<OSHDBCombinedIndex<Integer, OSHDBTimestamp>, MappingMonth> result = OSMContributionView
        .on(oshdb)
        .keytables(keytables)
        .areaOfInterest(bb)
        //Relations are excluded because they hold only little extra information and make this process very slow!
        .osmType(OSMType.NODE, OSMType.WAY)
        .timestamps("2004-01-01", "2019-02-01", OSHDBTimestamps.Interval.MONTHLY)
        .aggregateByGeometry(polygons)
        .aggregateByTimestamp(OSMContribution::getTimestamp)
        .map(new MapFunk())
        .reduce(new NewMapMonth(), new MonthCombiner());

    createStarted.stop();
    double toMinutes = (createStarted.getTime() / 1000.0) / 60.0;

    LOG.info("Query Finished, took " + toMinutes + " minutes");
    return result;
  }

  /*
 * This procedure identifies large scale events within osm data using the oshdb api
 * I define here events as large contributions in relation to the current development of the data base (i.e. relatively).
 * The procedures assumes that the accumulative number contribution actions (meaning the individual actions that make a contribution, e.g. deleting or adding a coordinate or a tag) follows 
 * an s-shaped (logistic) curve over time.
 * Accordingly, the procedure counts the accumulative number of actions for each month and fits a logistic curve of the type: a/(1+b*exp(-k*(t-u))), where t is a temporal index, with the data.
 * Differences between observed values and estimations ('errors') are calculated. To eliminate temporal dependency, the procedure uses lagged errors (lagged error at time t=
 * error at time t - error at time t-1).
 * The procedure normalizes the lagged errors and identifies significantly positive values at 95% confidence level as events.
 * For each event, the procedure records information regarding its date, number of active users, number of actions, maximal number of actions by a single user, relative change in the database size, 
 * and number of contributions by type.
   */
  public static Map<Integer, ArrayList<MappingEvent>> extractEvents(
      SortedMap<OSHDBCombinedIndex<Integer, OSHDBTimestamp>, MappingMonth> months,
      OSHDBDatabase oshdb,
      OSHDBJdbc keytables,
      Map<Integer, Polygon> polygons)
      throws Exception {

    LOG.info("Start processing result");
    StopWatch createStarted = StopWatch.createStarted();
    // saves objects of type Mapping_Event which stores the month of the event, the number of active mappers, number of contributions, and maximal number of contributions by one user
    final Map<Integer, ArrayList<MappingEvent>> out = new HashMap<>();

    //devide result into resulty per geometry
    SortedMap<Integer, SortedMap<OSHDBTimestamp, MappingMonth>> nest
        = OSHDBCombinedIndex.nest(months);

    File conv_file = new File("target/Convergence_errors.csv");
    FileWriter conv_writer = new FileWriter(conv_file);
    conv_writer.write("GeomNr.\n");

    //iterate
    nest.forEach((Integer geom, SortedMap<OSHDBTimestamp, MappingMonth> geomContributions) -> {
      ArrayList<MappingEvent> list = new ArrayList<>();

      // remove entries before first contribution
      while (geomContributions.get(geomContributions.firstKey())
          .get_contributions() == 0) {
        geomContributions.remove(geomContributions.firstKey());
        if (geomContributions.isEmpty()) {
          return;
        }
      }

      // remove entries after last contribution
      while (geomContributions.get(geomContributions.lastKey()).get_contributions() == 0) {
        geomContributions.remove(geomContributions.lastKey());
      }

      // create accumulative data
      SortedMap<OSHDBTimestamp, Integer> acc_result = new TreeMap<>();
      Integer conts = 0;
      for (Entry<OSHDBTimestamp, MappingMonth> entry : geomContributions.entrySet()) {
        conts += entry.getValue().get_contributions();
        acc_result.put(entry.getKey(), conts);
      }

      // create data for curve fitting
      SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
      Date date2007 = null;
      try {
        date2007 = dateFormat.parse("2007-09-30");
      } catch (ParseException e) {
        LOG.error("", e);
      }
      ArrayList<WeightedObservedPoint> points = new ArrayList<>();
      int i = 0;
      Iterator<OSHDBTimestamp> values = acc_result.keySet().iterator();
      while (values.hasNext()) {
        OSHDBTimestamp d = values.next();
        float v = acc_result.get(d);
        Date date = d.toDate();
        Boolean aft = date.after(date2007);
        if (aft) {
          WeightedObservedPoint point = new WeightedObservedPoint(1.0, i, v);
          points.add(point);
        }
        i++;
      }

      // fit curve
      MyFuncFitter fitter = new MyFuncFitter();
      double[] coeffs = null;

      try {
        //the next 10ish lines are for a timeout
        final Duration timeout = Duration.ofSeconds(60);

        CompletableFuture<double[]> handler = CompletableFuture.supplyAsync(() -> {
          LOG.debug(
              "Starting curvefitting. If this is the last message you see for a while something got stuck!");
          return fitter.fit(points);
        });
        coeffs = handler.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException | ExecutionException ex) {
        try {
          conv_writer.write(geom.toString() + "\n");
        } catch (IOException ex1) {
          LOG.error("", ex1);
        }
        LOG.warn("Geom " + geom + " did not converge!", ex);
        return;
      } catch (TimeoutException e) {
        try {
          conv_writer.write("timeout;##### " + geom.toString() + "\n");
        } catch (IOException ex1) {
          LOG.error("", ex1);
        }
        LOG.warn("Geom " + geom + " did timeout!", e);
        return;
      }

      // compute errors
      HashMap<OSHDBTimestamp, Double> errors = new HashMap<>();
      for (Entry<OSHDBTimestamp, MappingMonth> entry : geomContributions.entrySet()) {
        Double value = coeffs[0] / (1.0 + coeffs[1] * Math.exp(-coeffs[2] * (i - coeffs[3])));
        errors.put(entry.getKey(), acc_result.get(entry.getKey()) - value);
      }

      // get lagged errors
      HashMap<OSHDBTimestamp, Double> lagged_errors = new HashMap<>();
      for (i = 1; i < geomContributions.keySet().size(); i++) {
        Double value = errors.get(geomContributions.keySet().toArray()[i])
            - errors.get(geomContributions.keySet().toArray()[i - 1]);
        lagged_errors.put((OSHDBTimestamp) geomContributions.keySet().toArray()[i], value);
      }

      // compute mean and standard deviation for lagged errors
      Double mean = 0.;
      for (Double err : lagged_errors.values()) {
        mean += err;
      }
      mean /= lagged_errors.size();
      double std = 0.;
      for (double num : lagged_errors.values()) {
        std += Math.pow(num - mean, 2);
      }
      std = Math.sqrt(std / (lagged_errors.size() - 1.));

      Iterator<Entry<OSHDBTimestamp, MappingMonth>> iterator1 = geomContributions.entrySet()
          .iterator();
      iterator1.next();

      i = 1;
      while (iterator1.hasNext()) {
        Entry<OSHDBTimestamp, MappingMonth> next = iterator1.next();
        // identify events
        OSHDBTimestamp m_lag = (OSHDBTimestamp) geomContributions.keySet().toArray()[i - 1];
        Double error = (lagged_errors.get(next.getKey()) - mean) / std; // normalized error
        if (error > 1.644854) { // if error is positively significant at 95% - create event
          Date date = next.getKey().toDate();
          date.setMonth(date.getMonth());
          TimeZone tz = TimeZone.getTimeZone("UTC");
          DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
          df.setTimeZone(tz);
          String nowAsISO = df.format(date);
          OSHDBTimestamps ts = new OSHDBTimestamps(
              next.getKey().toString(),
              nowAsISO);
          int[] entity_edits = new int[3];
          try {
            entity_edits = EventFinder.queryEntityEdits(
                oshdb,
                keytables,
                polygons.get(geom),
                ts);
          } catch (Exception ex) {
            LOG.error("", ex);
          }
          MappingEvent e = new MappingEvent(next.getKey(), next.getValue(),
              next.getValue().getUser_counts().size(),
              acc_result.get(next.getKey()) - acc_result.get(m_lag),
              ((acc_result.get(next.getKey()) - (float) acc_result.get(m_lag))
              / acc_result.get(m_lag)),
              Collections.max(next.getValue().getUser_counts().values()),
              coeffs,
              next.getValue().get_type_counts(),
              entity_edits[0],
              entity_edits[1],
              entity_edits[2],
              error);
          list.add(e);
        }
        i++;
      }
      out.put(geom, list); // add to list of events
    });

    conv_writer.close();
    createStarted.stop();
    double toMinutes = (createStarted.getTime() / 1000.0) / 60.0;

    LOG.info("Pricessing Finished, took " + toMinutes + " minutes");
    return out;
  }

  private static Map<Integer, Polygon> getPolygons() throws IOException {
    LOG.info("Read Polygons");
    //read geometries
    ClassLoader classloader = Thread.currentThread().getContextClassLoader();
    InputStream is = classloader.getResourceAsStream("grid_20000_id.geojson");

    BufferedReader br = new BufferedReader(new InputStreamReader(is));
    StringBuilder sb = new StringBuilder();
    br.lines().forEach(line -> sb.append(line));
    String geoJson = sb.toString();

    Map<Integer, Polygon> geometries = new HashMap<>();
    FeatureCollection featureCollection = (FeatureCollection) GeoJSONFactory.create(geoJson);
    GeometryFactory gf = new GeometryFactory();
    GeoJSONReader gjr = new GeoJSONReader();

    for (Feature f : featureCollection.getFeatures()) {
      geometries.put((Integer) f.getProperties().get("id"),
          gf.createPolygon(gjr.read(f.getGeometry()).getCoordinates()));
    }

    LOG.info("Finished Reading Polygons");
    return geometries;
  }

  public static void writeOutput(Map<Integer, ArrayList<MappingEvent>> events) throws IOException {
    LOG.info("Save output");

    //write output
    File file = new File("target/MappingEvents.csv");

    //Create the file
    if (file.createNewFile()) {
      System.out.println("File is created!");
    } else {
      System.out.println("File already exists.");
    }

    try ( //Write Content
        FileWriter writer = new FileWriter(file)) {
      writer.write(
          "ID;GeomNr.;EventNr.;Timestamp;Users;Contributions;Change;MaxContributions;EditedEntitities;AverageGeomChanges;AverageTagChanges;Pvalue;Coeffs;TypeCounts\n");

      int[] id = {0};
      String pattern = "yyyy-MM";
      DateFormat df = new SimpleDateFormat(pattern);
      events.forEach((Integer geom, ArrayList<MappingEvent> ev) -> {
        if (ev.isEmpty()) {
          return;
        }
        System.out.println("");
        System.out.println("");
        System.out.println("Events for geom Nr. " + geom);
        System.out.println("");
        int[] eventnr = {1};
        ev.forEach((MappingEvent e) -> {
          try {
            writer.write(
                id[0] + ";"
                + geom + ";"
                + eventnr[0] + ";"
                + df.format(e.getTimestap().toDate()) + ";"
                + e.getUser_counts().size() + ";"
                + e.get_contributions() + ";"
                + e.getDeltakontrib() + ";"
                + e.getMaxCont() + ";"
                + e.getEntitiesChanged() + ";"
                + e.get_geom_change_average() + ";"
                + e.get_tag_change_average() + ";"
                + e.get_pvalue() + ";"
                + Arrays.toString(e.getCoeffs()) + ";"
                + e.get_type_counts().toString() + ";"
                + "\n"
            );
          } catch (IOException ex) {
            LOG.error("Could not write output.", ex);
          }
          System.out.println(
              df.format(e.getTimestap().toDate()) + ";"
              + e.getUser_counts().size() + ";"
              + e.get_contributions() + ";"
              + Collections.max(e.getUser_counts().values()) + ";"
              + e.getChange() + ";"
              + e.get_type_counts().values() + ";"
              + e.getMaxCont() + ";"
              + Arrays.toString(e.getCoeffs())
          );
          eventnr[0] += 1;
          id[0] += 1;
        });
      });
    }

    LOG.info("Finished");

  }

  private static int[] queryEntityEdits(
      OSHDBDatabase oshdb,
      OSHDBJdbc keytables,
      Polygon polygon,
      OSHDBTimestamps ts) throws Exception {

    LOG.info("Run follow-up query");

    StopWatch createStarted = StopWatch.createStarted();
    // collect contributions by month
    int[] result = OSMContributionView
        .on(oshdb)
        .keytables(keytables)
        .areaOfInterest(polygon)
        //Relations are excluded because they hold only little extra information and make this process very slow!
        .osmType(OSMType.NODE, OSMType.WAY)
        .timestamps(ts)
        .groupByEntity()
        .map((List<OSMContribution> contribList) -> {
          int[] currRes = new int[]{0, 0, 0};
          currRes[0] = 1;
          for (OSMContribution contrib : contribList) {
            int[] geomTagCount = MapFunk.getGeomTagCount(contrib);
            currRes[1] = geomTagCount[0];
            currRes[2] = geomTagCount[1];
          }
          return currRes;
        })
        .reduce(
            () -> new int[3],
            (int[] arr1, int[] arr2) ->
            new int[]{arr1[0] + arr2[0], arr1[1] + arr2[1], arr1[2] + arr2[2]}
        );

    createStarted.stop();
    double toMinutes = (createStarted.getTime() / 1000.0) / 60.0;

    LOG.info("Query Finished, took " + toMinutes + " minutes");

    return result;

  }

}
