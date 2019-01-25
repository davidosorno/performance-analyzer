package com.amazon.opendistro.performanceanalyzer.reader;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import com.amazon.opendistro.performanceanalyzer.DBUtils;
import com.amazon.opendistro.performanceanalyzer.metricsdb.MetricsDB;

import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SelectField;
import org.jooq.SelectHavingStep;
import org.jooq.SQLDialect;

import org.jooq.impl.DSL;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Snapshot of start/end events generated by customer initiated http operations like bulk and search.
 */
@SuppressWarnings("serial")
public class HttpRequestMetricsSnapshot implements Removable {
    private static final Logger LOG = LogManager.getLogger(HttpRequestMetricsSnapshot.class);
    private static final Long EXPIRE_AFTER = 600000L;
    private final DSLContext create;
    private final Long windowStartTime;
    private final String tableName;
    private List<String> columns;

    public enum Fields {
        rid, operation, indices, status, exception, itemCount, st, et, lat, count
    }

    public HttpRequestMetricsSnapshot(Connection conn, Long windowStartTime) throws Exception {
        this.create = DSL.using(conn, SQLDialect.SQLITE);
        this.windowStartTime = windowStartTime;
        this.tableName = "http_rq_" + windowStartTime;

        this.columns = new ArrayList<String>() { {
            this.add(Fields.rid.name());
            this.add(Fields.operation.name());
            this.add(Fields.indices.name());
            this.add(Fields.status.name());
            this.add(Fields.exception.name());
            this.add(Fields.itemCount.name());
            this.add(Fields.st.name());
            this.add(Fields.et.name());
        } };

        List<Field<?>> fields = new ArrayList<Field<?>>() { {
            this.add(DSL.field(DSL.name(Fields.rid.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.operation.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.indices.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.status.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.exception.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.itemCount.name()), Long.class));
            this.add(DSL.field(DSL.name(Fields.st.name()), Long.class));
            this.add(DSL.field(DSL.name(Fields.et.name()), Long.class));
        } };

        create.createTable(this.tableName)
            .columns(fields)
            .execute();
    }

    public void putStartMetric(Long startTime, Long itemCount, Map<String, String> dimensions) {
        Map<Field<?>, String> dimensionMap = new HashMap<>();
        for (Map.Entry<String, String> dimension: dimensions.entrySet()) {
                dimensionMap.put(DSL.field(DSL.name(dimension.getKey()), String.class),
                                    dimension.getValue());
        }
        create.insertInto(DSL.table(this.tableName))
            .set(DSL.field(DSL.name(Fields.st.name()), Long.class), startTime)
            .set(DSL.field(DSL.name(Fields.itemCount.name()), Long.class), itemCount)
            .set(dimensionMap)
            .execute();
    }

    public BatchBindStep startBatchPut() {
        List<Object> dummyValues = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            dummyValues.add(null);
        }
        return create.batch(create.insertInto(DSL.table(this.tableName)).values(dummyValues));
    }

    public void putEndMetric(Long endTime, Map<String, String> dimensions) {
        Map<Field<?>, String> dimensionMap = new HashMap<>();
        for (Map.Entry<String, String> dimension: dimensions.entrySet()) {
                dimensionMap.put(DSL.field(
                            DSL.name(dimension.getKey()), String.class),
                            dimension.getValue());
        }
        create.insertInto(DSL.table(this.tableName))
            .set(DSL.field(DSL.name(Fields.et.name()), Long.class), endTime)
            .set(dimensionMap)
            .execute();
    }

    public Result<Record> fetchAll() {
        return create.select().from(DSL.table(this.tableName)).fetch();
    }

    /**
     * This function returns a single row for each request.
     * We have a start and end event for each request and each event has different attributes.
     * This function aggregates all the data into a single row.
     *
     * Actual Table -
     *  |rid    |operation|indices |status|exception|itemCount|           st|           et|
     *  +-------+---------+--------+------+---------+---------+-------------+-------------+
     *  |1417935|search   |        |{null}|{null}   |        0|1535065254939|       {null}|
     *  |1418424|search   |{null}  |200   |         |   {null}|       {null}|1535065341025|
     *  |1418424|search   |sonested|{null}|{null}   |        0|1535065340730|       {null}|
     *  |1418435|search   |{null}  |200   |         |   {null}|       {null}|1535065343355|
     *
     * Returned Table
     *  |rid    |operation|indices |status|exception|itemCount|           st|           et|
     *  +-------+---------+--------+------+---------+---------+-------------+-------------+
     *  |1418424|search   |sonested|200   |         |        0|1535065340730|1535065341025|
     *  |1418435|search   |        |200   |         |        0|1535065254939|1535065343355|
     */
    public SelectHavingStep<Record> groupByRidSelect() {
        ArrayList<SelectField<?>> fields = new ArrayList<SelectField<?>>() { {
            this.add(DSL.field(DSL.name(Fields.rid.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.operation.name()), String.class));
        } };
        fields.add(DSL.max(DSL.field(Fields.st.name(), Long.class)).as(DSL.name(Fields.st.name())));
        fields.add(DSL.max(DSL.field(Fields.et.name(), Long.class)).as(DSL.name(Fields.et.name())));
        fields.add(DSL.max(DSL.field(Fields.indices.name())).as(DSL.name(Fields.indices.name())));
        fields.add(DSL.max(DSL.field(Fields.status.name())).as(DSL.name(Fields.status.name())));
        fields.add(DSL.max(DSL.field(Fields.exception.name())).as(DSL.name(Fields.exception.name())));
        fields.add(DSL.max(DSL.field(Fields.itemCount.name())).as(DSL.name(Fields.itemCount.name())));

        return create.select(fields).from(DSL.table(this.tableName))
            .groupBy(DSL.field(Fields.rid.name()));
    }

    /**
     * This function returns row with latency for each request.
     * We have a start and end event for each request and each event has different attributes.
     * This function aggregates all the data into a single row.
     *
     * Actual Table -
     *  |rid    |operation|indices |status|exception|itemCount|           st|           et|
     *  +-------+---------+--------+------+---------+---------+-------------+-------------+
     *  |1417935|search   |        |{null}|{null}   |        0|1535065254939|       {null}|
     *  |1418424|search   |{null}  |200   |         |   {null}|       {null}|1535065341025|
     *  |1418424|search   |sonested|{null}|{null}   |        0|1535065340730|       {null}|
     *  |1418435|search   |{null}  |200   |         |   {null}|       {null}|1535065343355|
     *
     * Returned Table
     *  |rid    |operation|indices |status|exception|itemCount|           st|           et|  lat|
     *  +-------+---------+--------+------+---------+---------+-------------+-------------+-----+
     *  |1418424|search   |sonested|200   |         |        0|1535065340730|1535065341025|  295|
     *  |1418435|search   |        |200   |         |        0|1535065254939|1535065343355|88416|
     */
    public SelectHavingStep<Record> fetchLatencyTable() {
        ArrayList<SelectField<?>> fields = new ArrayList<SelectField<?>>() { {
            this.add(DSL.field(DSL.name(Fields.rid.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.operation.name()), String.class));
            this.add(DSL.field(Fields.st.name(), Long.class));
            this.add(DSL.field(Fields.et.name(), Long.class));
            this.add(DSL.field(Fields.status.name()));
            this.add(DSL.field(Fields.indices.name()));
            this.add(DSL.field(Fields.exception.name()));
            this.add(DSL.field(Fields.itemCount.name()));
        } };
        fields.add(DSL.field(Fields.et.name()).minus(DSL.field(Fields.st.name())).as(DSL.name(Fields.lat.name())));
        return create.select(fields).from(groupByRidSelect())
            .where(DSL.field(Fields.et.name()).isNotNull().and(
                        DSL.field(Fields.st.name()).isNotNull()));
    }

    /**
     * This function aggregates rows by operation.
     * This is a performance optimization to avoid writing one entry per request back into metricsDB.
     * This function returns one row per operation.
     *
     * Latency Table -
     *  |rid    |operation|indices |status|exception|itemCount|           st|           et|lat|
     *  +-------+---------+--------+------+---------+---------+-------------+-------------+-----+
     *  |1418424|search   |sonested|200   |         |        0|1535065340730|1535065341025|295|
     *  |1418435|search   |sonested|200   |         |        0|1535065254939|1535065343355|305|
     *
     *  Returned Table -
     *  |operation|indices |status|exception|sum_lat|avg_lat|min_lat|max_lat|
     *  +---------+--------+------+---------+---------+-------------+-------+
     *  |search   |sonested|200   |         |    600|    300|    295|    305|
     */
    public Result<Record> fetchLatencyByOp() {
        ArrayList<SelectField<?>> fields = new ArrayList<SelectField<?>>() { {
            this.add(DSL.field(DSL.name(Fields.operation.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.status.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.indices.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.exception.name()), String.class));
            this.add(DSL.sum(DSL.field(DSL.name(Fields.itemCount.name()), Long.class))
                    .as(DBUtils.getAggFieldName(Fields.itemCount.name(), MetricsDB.SUM)));
            this.add(DSL.avg(DSL.field(DSL.name(Fields.itemCount.name()), Long.class))
                    .as(DBUtils.getAggFieldName(Fields.itemCount.name(), MetricsDB.AVG)));
            this.add(DSL.min(DSL.field(DSL.name(Fields.itemCount.name()), Long.class))
                    .as(DBUtils.getAggFieldName(Fields.itemCount.name(), MetricsDB.MIN)));
            this.add(DSL.max(DSL.field(DSL.name(Fields.itemCount.name()), Long.class))
                    .as(DBUtils.getAggFieldName(Fields.itemCount.name(), MetricsDB.MAX)));
            this.add(DSL.sum(DSL.field(DSL.name(Fields.lat.name()), Double.class))
                    .as(DBUtils.getAggFieldName(Fields.lat.name(), MetricsDB.SUM)));
            this.add(DSL.avg(DSL.field(DSL.name(Fields.lat.name()), Double.class))
                    .as(DBUtils.getAggFieldName(Fields.lat.name(), MetricsDB.AVG)));
            this.add(DSL.min(DSL.field(DSL.name(Fields.lat.name()), Double.class))
                    .as(DBUtils.getAggFieldName(Fields.lat.name(), MetricsDB.MIN)));
            this.add(DSL.max(DSL.field(DSL.name(Fields.lat.name()), Double.class))
                    .as(DBUtils.getAggFieldName(Fields.lat.name(), MetricsDB.MAX)));
            this.add(DSL.count().as(Fields.count.name()));
        } };
        ArrayList<Field<?>> groupByFields = new ArrayList<Field<?>>() { {
            this.add(DSL.field(DSL.name(Fields.operation.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.status.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.indices.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.exception.name()), String.class));
        } };

        return create.select(fields).from(fetchLatencyTable())
            .groupBy(groupByFields).fetch();
    }

    /**
     * This function returns requests with a missing end event.
     * A request maybe long running and the end event might not have occured in this snapshot.
     *
     * Actual Table -
     *  |rid    |operation|indices |status|exception|itemCount|           st|           et|
     *  +-------+---------+--------+------+---------+---------+-------------+-------------+
     *  |1417935|search   |        |{null}|{null}   |        0|1535065254939|       {null}|
     *  |1418424|search   |sonested|{null}|{null}   |        0|1535065340730|       {null}|
     *  |1418435|search   |{null}  |200   |         |   {null}|       {null}|1535065343355|
     *
     * Returned Table
     *  |rid    |operation|indices |status|exception|itemCount|           st|           et|
     *  +-------+---------+--------+------+---------+---------+-------------+-------------+
     *  |1418424|search   |sonested|200   |         |        0|1535065340730|             |
     */
    public SelectHavingStep<Record> fetchInflightRequests() {
        ArrayList<SelectField<?>> fields = new ArrayList<SelectField<?>>() { {
            this.add(DSL.field(DSL.name(Fields.rid.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.operation.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.indices.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.status.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.exception.name()), String.class));
            this.add(DSL.field(DSL.name(Fields.itemCount.name()), Long.class));
            this.add(DSL.field(Fields.st.name(), Long.class));
            this.add(DSL.field(Fields.et.name(), Long.class));
        } };

        return create.select(fields).from(groupByRidSelect())
            .where(DSL.field(Fields.st.name()).isNotNull()
                    .and(DSL.field(Fields.et.name()).isNull())
                    .and(DSL.field(Fields.st.name()).gt(this.windowStartTime - EXPIRE_AFTER)));
    }

    public String getTableName() {
        return this.tableName;
    }

    public void remove() {
        LOG.info("Dropping table - {}", this.tableName);
        create.dropTable(DSL.table(this.tableName)).execute();
    }

    public void rolloverInflightRequests(HttpRequestMetricsSnapshot prevSnap) {
        //Fetch all entries that have not ended and write to current table.
        create.insertInto(DSL.table(this.tableName)).select(
                create.select().from(prevSnap.fetchInflightRequests())).execute();
    }
}

