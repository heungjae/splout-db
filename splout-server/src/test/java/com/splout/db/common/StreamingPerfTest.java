package com.splout.db.common;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.almworks.sqlite4java.SQLiteConnection;
import com.hazelcast.core.Hazelcast;
import com.splout.db.dnode.DNode;
import com.splout.db.dnode.DNodeClient;
import com.splout.db.dnode.DNodeHandler;
import com.splout.db.dnode.DNodeProperties;
import com.splout.db.engine.ResultAndCursorId;
import com.splout.db.engine.ResultSerializer;
import com.splout.db.qnode.QNode;
import com.splout.db.qnode.QNodeHandler;
import com.splout.db.qnode.QNodeProperties;
import com.splout.db.qnode.beans.DeployRequest;
import com.splout.db.thrift.DNodeService;

/**
 * A work-in-progress program for running performance tests when reading bulk data from Splout 
 */
public class StreamingPerfTest {

  private final static Log log = LogFactory.getLog(StreamingPerfTest.class);

  public static void main(String[] args) throws Throwable {
    // Set to true to delete previous deployments, thus forcing a re-deploy
    boolean resetData = true;

    SploutConfiguration conf = SploutConfiguration.getTestConfig();

    conf.setProperty(CommonProperties.ENABLE_CURSORS, true);
    conf.setProperty(DNodeProperties.MAX_RESULTS_PER_QUERY, 5000);
    conf.setProperty(QNodeProperties.DISABLE_BINARY_PROTOCOL, false);

    final QNode qnode = TestUtils.getTestQNode(conf, new QNodeHandler());
    final DNode dnode = TestUtils.getTestDNode(conf, new DNodeHandler(), "spft-dnode-" + StreamingPerfTest.class.getName(), resetData);

    File bigDbFile = new File("big_db");

    if (resetData) {
      FileUtils.deleteQuietly(bigDbFile);
    }

    boolean deploy = true;
    final String tablespace = "test";
    final String table = "big_table";
    final int nRecords = 10000000;
    final int partition = 0;

    try {

      if (!bigDbFile.exists()) {
        bigDbFile.mkdirs();
        log.info("Creating big database: " + bigDbFile);
        SQLiteConnection conn = new SQLiteConnection(new File(bigDbFile, partition + ".db"));
        conn.open(true);
        conn.exec("BEGIN");
        conn.exec("CREATE TABLE " + table + " (foo1 TEXT, foo2 INT, foo3 DOUBLE, foo4 TEXT);");
        for (int i = 0; i < nRecords; i++) {
          conn.exec("INSERT INTO big_table VALUES ('blahblahblah', 1000, 10.0, 'blohblohbloh');");
          if (i % 10000 == 0) {
            log.info(i + " written.");
          }
        }
        conn.exec("COMMIT");
        conn.dispose();
      }

      new TestUtils.NotWaitingForeverCondition() {

        @Override
        public boolean endCondition() throws Exception {
          return qnode.getHandler().getDNodeList().size() > 0;
        }
      }.waitAtMost(Integer.MAX_VALUE);

      if (qnode.getHandler().allTablespaceVersions(tablespace).size() > 0) {
        deploy = false;
        log.warn("Tablespace found for '" + tablespace + "' -> " + qnode.getHandler().allTablespaceVersions(tablespace)
            + ", not deploying.");
      } else {
        log.warn("No tablespace '" + tablespace + "' in test Splout, deploying it.");
      }

      if (deploy) {
        log.info("Deploying ...");

        DeployRequest req = new DeployRequest();
        req.setTablespace(tablespace);
        req.setData_uri(bigDbFile.toURI().toString());
        req.setPartitionMap(PartitionMap.oneShardOpenedMap().getPartitionEntries());
        req.setReplicationMap(Arrays.asList(new ReplicationEntry(partition, dnode.getAddress())));

        List<DeployRequest> l = new ArrayList<DeployRequest>();
        l.add(req);

        qnode.getHandler().deploy(l);

        // Wait until deploy finished
        new TestUtils.NotWaitingForeverCondition() {

          @Override
          public boolean endCondition() throws Exception {
            return qnode.getHandler().allTablespaceVersions(tablespace).size() > 0;
          }
        }.waitAtMost(Integer.MAX_VALUE);

        log.info("... Deploy finished.");
      }

      long version = qnode.getHandler().allTablespaceVersions(tablespace).entrySet().iterator().next().getKey();

      // Connect to the DNode
      DNodeService.Client client = DNodeClient.get(dnode.getAddress());
      int cursorId = ResultAndCursorId.NO_CURSOR;

      log.info("Reading results from partition " + partition + " of '" + tablespace + "' version [" + version + "] using Thrift...");

      long start = System.currentTimeMillis();

      do {
        ResultAndCursorId cursor = ResultSerializer.deserialize(client.binarySqlQuery(tablespace, version, partition, "SELECT * FROM "
            + table, cursorId));
        cursorId = cursor.getCursorId();
      } while (cursorId != ResultAndCursorId.NO_CURSOR);

      long end = System.currentTimeMillis();

      log.info("Read " + nRecords + " in " + (end - start));
      DNodeClient.close(client);

    } finally {
      qnode.close();
      dnode.stop();
      Hazelcast.shutdownAll();
    }
  }
}
