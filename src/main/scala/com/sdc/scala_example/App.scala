package com.sdc.scala_example

import com.sdc.scala_example.network.Node
import com.sdc.scala_example.network.Link
import org.apache.spark.sql.SparkSession
import org.apache.spark.SparkConf
import com.sdc.scala_example.network.Link
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.types.StructType
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Dataset
import org.slf4j.LoggerFactory
import org.apache.spark.graphx.lib.ShortestPaths
import org.apache.spark.graphx.Graph
import org.apache.spark.graphx.Edge
import com.sdc.scala_example.shortestpath.ShortestPathsCustom
import com.sdc.scala_example.command_line.CommandLineManager
import com.sdc.scala_example.command_line.PARAMETER
import com.sdc.scala_example.command_line.RUN_TYPE
import com.sdc.scala_example.osm.OsmParquetConverter
import java.io.File
import com.sdc.scala_example.osm.GraphParquetImporter
import com.sdc.scala_example.shortestpath.ShortestPathsCustom.COST_FUNCTION
import com.sdc.scala_example.command_line.AppContext
import com.sdc.scala_example.shortestpath.VertexShortestPath
import com.sdc.scala_example.geometry.GeometryUtils
import com.vividsolutions.jts.geom.Point
import org.apache.spark.sql.SQLContext

/**
 * @author ${user.name}
 */
object App {

    val LOG = LoggerFactory.getLogger(classOf[App])
    val APP_NAME = "scala-graphx-example"
    val SHORTEST_PATH_VERTICES_OUTPUT_FILE_NAME = "shortest-path-vertices.parquet"
    
    
    def main(args : Array[String]) {

        LOG.info("#################################### START ####################################");

        // read data from command line
        val commandLineManager : CommandLineManager = CommandLineManager.newBuilder().withArgs(args).build();
        if (!commandLineManager.hasHelp()) {

            var session : SparkSession = null
            try {
                val appContext = commandLineManager.parse()

                session = initSpark(commandLineManager)

                if (appContext.getRunType == RUN_TYPE.OSM_CONVERTER) {

                    val context = OsmParquetConverter.Context(new File(appContext.getOsmNodesFilePath)
                    , new File(appContext.getOsmWaysFilePath), appContext.getOutputDir
                    , appContext.getNodesRepartitionOutput, appContext.getLinksRepartitionOutput)
                    OsmParquetConverter.convertToNetwork(session, context)

                } else if (appContext.getRunType == RUN_TYPE.SHORTEST_PATH)

                    runShortestPath(appContext, session)

                else
                    LOG.warn("No available run type specified: %s".format(appContext.getRunType))

            } catch {
                case e : Exception => LOG.error("General error running application", e)
            } finally {
                if (session != null)
                    session.close()
            }
        }

        LOG.info("#################################### END ####################################");

    }

    private def runShortestPath(appContext : AppContext, session : org.apache.spark.sql.SparkSession) = {
        

        val sqlContext = new SQLContext(session.sparkContext)
        import sqlContext.implicits._
        
        val context = GraphParquetImporter.Context(new File(appContext.getNodesFilePath), new File(appContext.getLinksFilePath))
        val network = GraphParquetImporter.importToNetwork(session, context)
        val graph = network.graph
        graph.cache()
        LOG.info("Graph number of vertices: %d".format(graph.vertices.count()))
        LOG.info("Graph number of edges: %d".format(graph.edges.count()))
        val costFunction = COST_FUNCTION.fromValue(appContext.getCostFunction)
        
        val sourcePoint :Point = appContext.getSpSource
        var distanceBBox = 1000
        var ur = GeometryUtils.determineCoordinateInDistance(sourcePoint.getX, sourcePoint.getY, 45, distanceBBox)
        var bl = GeometryUtils.determineCoordinateInDistance(sourcePoint.getX, sourcePoint.getY, 45 + 180, distanceBBox)
        //todo_here
        var nodeDF = network.nodesDF
        nodeDF.cache
        var nodesInBBox = nodeDF.select("*")
        .where($"longitude" <= ur.x && $"longitude" >= bl.x && $"latitude" <= ur.y && $"latitude" >= bl.y) 
        
        val sourceId = 101179103
        
        val spResult = ShortestPathsCustom.run(graph, sourceId, costFunction)
        val verticesRDD = spResult.vertices
        val verteicesRowRDD = verticesRDD.map(t => {
            Row.fromSeq(Seq(t._1, t._2.getMinCost(), t._2.getPredecessorLink()))
        })
        
        val verticesDF = session.createDataFrame(verteicesRowRDD, ShortestPathsCustom.VERTEX_SHORTEST_PATH_SCHEMA)
        verticesDF.write.parquet(appContext.getOutputDir + SHORTEST_PATH_VERTICES_OUTPUT_FILE_NAME)
    }

    private def initSpark(commandLineManager : CommandLineManager) : SparkSession = {

        var sparkMaster = commandLineManager.getOptionValue(PARAMETER.SPARK_MASTER.getLongOpt())
        var conf : SparkConf = new SparkConf()
        if (sparkMaster != null) {
            conf.setMaster(sparkMaster)
        }
        conf.setAppName(APP_NAME);
        val session = SparkSession.builder().config(conf).getOrCreate()
        session
    }
}
