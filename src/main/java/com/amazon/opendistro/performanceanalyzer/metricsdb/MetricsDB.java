package com.amazon.opendistro.performanceanalyzer.metricsdb;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import com.amazon.opendistro.performanceanalyzer.DBUtils;
import com.amazon.opendistro.performanceanalyzer.reader.Removable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.TableLike;
import org.jooq.impl.DSL;

import com.amazon.opendistro.performanceanalyzer.config.PluginSettings;

/**
 * Ondisk database that holds a 5 second snapshot of all metrics.
 * We create one table per metric. Every row contains four aggregations and any other relevant dimensions.
 *
 * Eg:
 * CPU table
 * |sum|avg|max|min|   index|shard|role|
 * +---+---+---+---+--------+-----+----+
 * |  5|2.5|  3|  2|sonested|    1| N/A|
 *
 * RSS table
 * |sum|avg|max|min|    index|shard|role|
 * +---+---+---+---+---------+-----+----+
 * | 30| 15| 20| 10|nyc_taxis|    1| N/A|
 */
@SuppressWarnings("serial")
public class MetricsDB implements Removable {

    private static final Logger LOG = LogManager.getLogger(MetricsDB.class);

    private static final String DB_FILE_PREFIX_PATH_DEFAULT = "/tmp/metricsdb_";
    private static final String DB_FILE_PREFIX_PATH_CONF_NAME = "metrics-db-file-prefix-path";
    private static final String DB_URL = "jdbc:sqlite:";
    private final Connection conn;
    private final DSLContext create;
    public static final String SUM = "sum";
    public static final String AVG = "avg";
    public static final String MIN = "min";
    public static final String MAX = "max";
    private long windowStartTime;

    public String getDBFilePath() {
        return PluginSettings.instance().getSettingValue(DB_FILE_PREFIX_PATH_CONF_NAME, DB_FILE_PREFIX_PATH_DEFAULT)
                + Long.toString(windowStartTime);
    }

    public MetricsDB(long windowStartTime) throws Exception {
        this.windowStartTime = windowStartTime;
        String url = DB_URL + getDBFilePath();
        conn = DriverManager.getConnection(url);
        conn.setAutoCommit(false);
        create = DSL.using(conn, SQLDialect.SQLITE);
    }

    public void close() throws Exception {
        conn.close();
    }

    public void createMetric(Metric<?> metric, List<String> dimensions) {
        if (DBUtils.checkIfTableExists(create, metric.getName())) {
            return;
        }

        List<Field<?>> fields = DBUtils.getFieldsFromList(dimensions);
        fields.add(DSL.field(SUM, metric.getValueType()));
        fields.add(DSL.field(AVG, metric.getValueType()));
        fields.add(DSL.field(MIN, metric.getValueType()));
        fields.add(DSL.field(MAX, metric.getValueType()));
        create.createTable(metric.getName())
            .columns(fields)
            .execute();
    }

    public BatchBindStep startBatchPut(Metric<?> metric, List<String> dimensions) {
        List<?> dummyValues = new ArrayList<>();
        for (String dim: dimensions) {
            dummyValues.add(null);
        }
        //Finally add sum, avg, min, max
        dummyValues.add(null);
        dummyValues.add(null);
        dummyValues.add(null);
        dummyValues.add(null);
        return create.batch(create.insertInto(DSL.table(metric.getName())).values(dummyValues));
    }

    public BatchBindStep startBatchPut(String tableName, int dimNum) {
        if (dimNum < 1 || !DBUtils.checkIfTableExists(create, tableName)) {
            throw new IllegalArgumentException(String
                    .format("Incorrect arguments %s, %d", tableName, dimNum));
        }
        List<?> dummyValues = new ArrayList<>(dimNum);
        for (int i = 0; i < dimNum; i++) {
            dummyValues.add(null);
        }

        return create.batch(
                create.insertInto(DSL.table(tableName)).values(dummyValues));
    }

    public void putMetric(Metric<Double> metric,
            Dimensions dimensions,
            long windowStartTime) {
        create.insertInto(DSL.table(metric.getName()))
            .set(DSL.field(SUM, Double.class), metric.getSum())
            .set(DSL.field(AVG, Double.class), metric.getAvg())
            .set(DSL.field(MIN, Double.class), metric.getMin())
            .set(DSL.field(MAX, Double.class), metric.getMax())
            .set(dimensions.getFieldMap())
            .execute();
    }

    //We have a table per metric. We do a group by/aggregate on
    //every dimension and return all the metric tables.
    public List<TableLike<Record>> getAggregatedMetricTables(List<String> metrics,
            List<String> aggregations, List<String> dimensions) throws Exception {
        List<TableLike<Record>> tList = new ArrayList<>();
        List<Field<?>> groupByFields = DBUtils.getFieldsFromList(dimensions);

        for (int i = 0; i < metrics.size(); i++) {
            String metric = metrics.get(i);
            List<Field<?>> selectFields = DBUtils.getFieldsFromList(dimensions);
            String aggType = aggregations.get(i);
            if (aggType.equals(SUM)) {
                Field<Double> field = DSL.field(SUM, Double.class);
                selectFields.add(DSL.sum(field).as(metric));
            } else if (aggType.equals(AVG)) {
                Field<Double> field = DSL.field(AVG, Double.class);
                selectFields.add(DSL.avg(field).as(metric));
            } else if (aggType.equals(MIN)) {
                Field<Double> field = DSL.field(MIN, Double.class);
                selectFields.add(DSL.min(field).as(metric));
            } else if (aggType.equals(MAX)) {
                Field<Double> field = DSL.field(MAX, Double.class);
                selectFields.add(DSL.max(field).as(metric));
            } else {
                throw new Exception("Unknown agg type");
            }
            tList.add(create.select(selectFields)
                    .from(DSL.table(metric))
                    .groupBy(groupByFields)
                    .asTable());
        }
        return tList;
    }


    /**
     * query metrics from different tables and merge to one table.
     *
     * getAggregatedMetricTables returns tables like:
     * +-----+---------+-----+
     * |shard|indexName|  cpu|
     * +-----+---------+-----+
     * |0    |sonested |   10|
     * |1    |sonested |   20|
     *
     * +-----+---------+-----+
     * |shard|indexName|  rss|
     * +-----+---------+-----+
     * |0    |sonested |   54|
     * |2    |sonested |   47|
     *
     * We select metrics from each table and union them:
     * +-----+---------+-----+-----+
     * |shard|indexName|  cpu|  rss|
     * +-----+---------+-----+-----+
     * |0    |sonested |   10| null|
     * |1    |sonested |   20| null|
     * |0    |sonested | null|   54|
     * |2    |sonested | null|   47|
     *
     * Then, we group by dimensions and return following table:
     * +-----+---------+-----+-----+
     * |shard|indexName|  cpu|  rss|
     * +-----+---------+-----+-----+
     * |0    |sonested |   10|   54|
     * |1    |sonested |   20| null|
     * |2    |sonested | null|   47|
     *
     * */
    public Result<Record> queryMetric(List<String> metrics,
            List<String> aggregations, List<String> dimensions) throws Exception {
        List<TableLike<Record>> tList = getAggregatedMetricTables(metrics,
                aggregations, dimensions);

        //Join all the individual metric tables to generate the final table.
        Select<Record> finalTable = null;
        for (int i = 0; i < tList.size(); i++) {
            List<Field<?>> selectFields = DBUtils.getFieldsFromList(dimensions);
            for (int j = 0; j < metrics.size(); j ++) {
                if (i == j) {
                    selectFields.add(DSL.field(metrics.get(i), Double.class).as(metrics.get(j)));
                } else {
                    selectFields.add(DSL.val(null, Double.class).as(metrics.get(j)));
                }
            }
            Select<Record> curTable = create.select(selectFields).from(tList.get(i));

            if (finalTable == null) {
                finalTable = curTable;
            } else {
                finalTable = finalTable.union(curTable);
            }
        }

        List<Field<?>> allFields = DBUtils.getFieldsFromList(dimensions);
        for (String metric : metrics) {
            allFields.add(DSL.max(DSL.field(metric, Double.class)).as(metric));
        }
        List<Field<?>> groupByFields = DBUtils.getFieldsFromList(dimensions);
        return create.select(allFields).from(finalTable).groupBy(groupByFields).fetch();
    }

    public void commit() throws Exception {
        conn.commit();
    }

    @Override
    public void remove() throws Exception {
       conn.close();
       File dbFile = new File(getDBFilePath());
       if (!dbFile.delete()) {
           LOG.error("Failed to delete File - {}", getDBFilePath());
       }
    }

    public Result<Record> queryMetric(String metric) {
        return create.select().from(DSL.table(metric)).fetch();
    }

    public boolean metricExists(String metric) {
        return DBUtils.checkIfTableExists(create, metric);
    }
}


