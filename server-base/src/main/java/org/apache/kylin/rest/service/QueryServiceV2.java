/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.rest.service;

import static org.apache.kylin.common.util.CheckUtil.checkCondition;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.commons.lang.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.QueryContext;
import org.apache.kylin.common.debug.BackdoorToggles;
import org.apache.kylin.common.exceptions.ResourceLimitExceededException;
import org.apache.kylin.common.util.SetThreadName;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.metadata.model.DataModelDesc;
import org.apache.kylin.metadata.model.JoinDesc;
import org.apache.kylin.metadata.model.JoinTableDesc;
import org.apache.kylin.metadata.model.ModelDimensionDesc;
import org.apache.kylin.metadata.model.TableRef;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.query.relnode.OLAPContext;
import org.apache.kylin.query.util.QueryUtil;
import org.apache.kylin.rest.constant.Constant;
import org.apache.kylin.rest.exception.BadRequestException;
import org.apache.kylin.rest.exception.InternalErrorException;
import org.apache.kylin.rest.metrics.QueryMetricsFacade;
import org.apache.kylin.rest.model.ColumnMetaWithType;
import org.apache.kylin.rest.model.SelectedColumnMeta;
import org.apache.kylin.rest.model.TableMetaWithType;
import org.apache.kylin.rest.msg.Message;
import org.apache.kylin.rest.msg.MsgPicker;
import org.apache.kylin.rest.request.PrepareSqlRequest;
import org.apache.kylin.rest.request.SQLRequest;
import org.apache.kylin.rest.response.SQLResponse;
import org.apache.kylin.rest.util.TableauInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;

import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;

/**
 * Created by luwei on 17-4-24.
 */
@Component("queryServiceV2")
public class QueryServiceV2 extends QueryService {
    private static final Logger logger = LoggerFactory.getLogger(QueryServiceV2.class);

    @Autowired
    @Qualifier("cacheService")
    private CacheService cacheService;

    @Autowired
    @Qualifier("modelMgmtServiceV2")
    private ModelServiceV2 modelServiceV2;

    public SQLResponse doQueryWithCache(SQLRequest sqlRequest) {
        Message msg = MsgPicker.getMsg();

        KylinConfig kylinConfig = KylinConfig.getInstanceFromEnv();
        String serverMode = kylinConfig.getServerMode();
        if (!(Constant.SERVER_MODE_QUERY.equals(serverMode.toLowerCase()) || Constant.SERVER_MODE_ALL.equals(serverMode.toLowerCase()))) {
            throw new BadRequestException(String.format(msg.getQUERY_NOT_ALLOWED(), serverMode));
        }
        if (StringUtils.isBlank(sqlRequest.getProject())) {
            throw new BadRequestException(msg.getEMPTY_PROJECT_NAME());
        }

        if (sqlRequest.getBackdoorToggles() != null)
            BackdoorToggles.addToggles(sqlRequest.getBackdoorToggles());

        final QueryContext queryContext = QueryContext.current();

        try (SetThreadName ignored = new SetThreadName("Query %s", queryContext.getQueryId())) {
            String sql = sqlRequest.getSql();
            String project = sqlRequest.getProject();
            logger.info("Using project: " + project);
            logger.info("The original query:  " + sql);

            if (!sql.toLowerCase().contains("select")) {
                logger.debug("Directly return exception as not supported");
                throw new BadRequestException(msg.getNOT_SUPPORTED_SQL());
            }

            long startTime = System.currentTimeMillis();

            SQLResponse sqlResponse = null;
            boolean queryCacheEnabled = checkCondition(kylinConfig.isQueryCacheEnabled(), "query cache disabled in KylinConfig") && //
                    checkCondition(!BackdoorToggles.getDisableCache(), "query cache disabled in BackdoorToggles");

            if (queryCacheEnabled) {
                sqlResponse = searchQueryInCache(sqlRequest);
            }

            try {
                if (null == sqlResponse) {
                    sqlResponse = query(sqlRequest);

                    long durationThreshold = kylinConfig.getQueryDurationCacheThreshold();
                    long scanCountThreshold = kylinConfig.getQueryScanCountCacheThreshold();
                    long scanBytesThreshold = kylinConfig.getQueryScanBytesCacheThreshold();
                    sqlResponse.setDuration(System.currentTimeMillis() - startTime);
                    logger.info("Stats of SQL response: isException: {}, duration: {}, total scan count {}", //
                            String.valueOf(sqlResponse.getIsException()), String.valueOf(sqlResponse.getDuration()), String.valueOf(sqlResponse.getTotalScanCount()));
                    if (checkCondition(queryCacheEnabled, "query cache is disabled") //
                            && checkCondition(!sqlResponse.getIsException(), "query has exception") //
                            && checkCondition(sqlResponse.getDuration() > durationThreshold || sqlResponse.getTotalScanCount() > scanCountThreshold || sqlResponse.getTotalScanBytes() > scanBytesThreshold, //
                            "query is too lightweight with duration: {} (threshold {}), scan count: {} (threshold {}), scan bytes: {} (threshold {})", sqlResponse.getDuration(), durationThreshold, sqlResponse.getTotalScanCount(), scanCountThreshold, sqlResponse.getTotalScanBytes(), scanBytesThreshold)
                            && checkCondition(sqlResponse.getResults().size() < kylinConfig.getLargeQueryThreshold(), "query response is too large: {} ({})", sqlResponse.getResults().size(), kylinConfig.getLargeQueryThreshold())) {
                        cacheManager.getCache(SUCCESS_QUERY_CACHE).put(new Element(sqlRequest, sqlResponse));
                    }

                } else {
                    sqlResponse.setDuration(System.currentTimeMillis() - startTime);
                    sqlResponse.setTotalScanCount(0);
                    sqlResponse.setTotalScanBytes(0);
                }

                checkQueryAuth(sqlResponse);

            } catch (Throwable e) { // calcite may throw AssertError
                logger.error("Exception when execute sql", e);
                String errMsg = QueryUtil.makeErrorMsgUserFriendly(e);

                sqlResponse = new SQLResponse(null, null, 0, true, errMsg);
                sqlResponse.setTotalScanCount(queryContext.getScannedRows());
                sqlResponse.setTotalScanBytes(queryContext.getScannedBytes());

                if (queryCacheEnabled && e.getCause() != null && e.getCause() instanceof ResourceLimitExceededException) {
                    Cache exceptionCache = cacheManager.getCache(EXCEPTION_QUERY_CACHE);
                    exceptionCache.put(new Element(sqlRequest, sqlResponse));
                }
            }

            logQuery(sqlRequest, sqlResponse);

            QueryMetricsFacade.updateMetrics(sqlRequest, sqlResponse);

            if (sqlResponse.getIsException())
                throw new InternalErrorException(sqlResponse.getExceptionMessage());

            return sqlResponse;

        } finally {
            BackdoorToggles.cleanToggles();
            QueryContext.reset();
        }
    }

    public SQLResponse query(SQLRequest sqlRequest) throws Exception {
        try {
            final String user = SecurityContextHolder.getContext().getAuthentication().getName();
            badQueryDetector.queryStart(Thread.currentThread(), sqlRequest, user);

            return queryWithSqlMassage(sqlRequest);

        } finally {
            badQueryDetector.queryEnd(Thread.currentThread());
        }
    }

    private SQLResponse queryWithSqlMassage(SQLRequest sqlRequest) throws Exception {
        String userInfo = SecurityContextHolder.getContext().getAuthentication().getName();
        final Collection<? extends GrantedAuthority> grantedAuthorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        for (GrantedAuthority grantedAuthority : grantedAuthorities) {
            userInfo += ",";
            userInfo += grantedAuthority.getAuthority();
        }

        SQLResponse fakeResponse = TableauInterceptor.tableauIntercept(sqlRequest.getSql());
        if (null != fakeResponse) {
            logger.debug("Return fake response, is exception? " + fakeResponse.getIsException());
            return fakeResponse;
        }

        String correctedSql = QueryUtil.massageSql(sqlRequest.getSql(), sqlRequest.getLimit(), sqlRequest.getOffset());
        if (!correctedSql.equals(sqlRequest.getSql())) {
            logger.info("The corrected query: " + correctedSql);

            //CAUTION: should not change sqlRequest content!
            //sqlRequest.setSql(correctedSql);
        }

        // add extra parameters into olap context, like acceptPartial
        Map<String, String> parameters = new HashMap<String, String>();
        parameters.put(OLAPContext.PRM_USER_AUTHEN_INFO, userInfo);
        parameters.put(OLAPContext.PRM_ACCEPT_PARTIAL_RESULT, String.valueOf(sqlRequest.isAcceptPartial()));
        OLAPContext.setParameters(parameters);
        // force clear the query context before a new query
        OLAPContext.clearThreadLocalContexts();

        return execute(correctedSql, sqlRequest);

    }

    /**
     * @param correctedSql
     * @param sqlRequest
     * @return
     * @throws Exception
     */
    private SQLResponse execute(String correctedSql, SQLRequest sqlRequest) throws Exception {
        Connection conn = null;
        Statement stat = null;
        ResultSet resultSet = null;

        List<List<String>> results = Lists.newArrayList();
        List<SelectedColumnMeta> columnMetas = Lists.newArrayList();

        try {
            conn = cacheService.getOLAPDataSource(sqlRequest.getProject()).getConnection();

            if (sqlRequest instanceof PrepareSqlRequest) {
                PreparedStatement preparedState = conn.prepareStatement(correctedSql);
                processStatementAttr(preparedState, sqlRequest);

                for (int i = 0; i < ((PrepareSqlRequest) sqlRequest).getParams().length; i++) {
                    setParam(preparedState, i + 1, ((PrepareSqlRequest) sqlRequest).getParams()[i]);
                }

                resultSet = preparedState.executeQuery();
            } else {
                stat = conn.createStatement();
                processStatementAttr(stat, sqlRequest);
                resultSet = stat.executeQuery(correctedSql);
            }

            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();

            // Fill in selected column meta
            for (int i = 1; i <= columnCount; ++i) {
                columnMetas.add(new SelectedColumnMeta(metaData.isAutoIncrement(i), metaData.isCaseSensitive(i), metaData.isSearchable(i), metaData.isCurrency(i), metaData.isNullable(i), metaData.isSigned(i), metaData.getColumnDisplaySize(i), metaData.getColumnLabel(i), metaData.getColumnName(i), metaData.getSchemaName(i), metaData.getCatalogName(i), metaData.getTableName(i), metaData.getPrecision(i), metaData.getScale(i), metaData.getColumnType(i), metaData.getColumnTypeName(i), metaData.isReadOnly(i), metaData.isWritable(i), metaData.isDefinitelyWritable(i)));
            }

            // fill in results
            while (resultSet.next()) {
                List<String> oneRow = Lists.newArrayListWithCapacity(columnCount);
                for (int i = 0; i < columnCount; i++) {
                    oneRow.add((resultSet.getString(i + 1)));
                }

                results.add(oneRow);
            }
        } finally {
            close(resultSet, stat, conn);
        }

        boolean isPartialResult = false;
        String cube = "";
        StringBuilder sb = new StringBuilder("Processed rows for each storageContext: ");
        if (OLAPContext.getThreadLocalContexts() != null) { // contexts can be null in case of 'explain plan for'
            for (OLAPContext ctx : OLAPContext.getThreadLocalContexts()) {
                if (ctx.realization != null) {
                    isPartialResult |= ctx.storageContext.isPartialResultReturned();
                    cube = ctx.realization.getName();
                    sb.append(ctx.storageContext.getProcessedRowCount()).append(" ");
                }
            }
        }
        logger.info(sb.toString());

        SQLResponse response = new SQLResponse(columnMetas, results, cube, 0, false, null, isPartialResult);
        response.setTotalScanCount(QueryContext.current().getScannedRows());
        response.setTotalScanBytes(QueryContext.current().getScannedBytes());

        return response;
    }

    /**
     * @param preparedState
     * @param param
     * @throws SQLException
     */
    private void setParam(PreparedStatement preparedState, int index, PrepareSqlRequest.StateParam param) throws SQLException {
        boolean isNull = (null == param.getValue());

        Class<?> clazz;
        try {
            clazz = Class.forName(param.getClassName());
        } catch (ClassNotFoundException e) {
            throw new InternalErrorException(e);
        }

        ColumnMetaData.Rep rep = ColumnMetaData.Rep.of(clazz);

        switch (rep) {
            case PRIMITIVE_CHAR:
            case CHARACTER:
            case STRING:
                preparedState.setString(index, isNull ? null : String.valueOf(param.getValue()));
                break;
            case PRIMITIVE_INT:
            case INTEGER:
                preparedState.setInt(index, isNull ? 0 : Integer.valueOf(param.getValue()));
                break;
            case PRIMITIVE_SHORT:
            case SHORT:
                preparedState.setShort(index, isNull ? 0 : Short.valueOf(param.getValue()));
                break;
            case PRIMITIVE_LONG:
            case LONG:
                preparedState.setLong(index, isNull ? 0 : Long.valueOf(param.getValue()));
                break;
            case PRIMITIVE_FLOAT:
            case FLOAT:
                preparedState.setFloat(index, isNull ? 0 : Float.valueOf(param.getValue()));
                break;
            case PRIMITIVE_DOUBLE:
            case DOUBLE:
                preparedState.setDouble(index, isNull ? 0 : Double.valueOf(param.getValue()));
                break;
            case PRIMITIVE_BOOLEAN:
            case BOOLEAN:
                preparedState.setBoolean(index, !isNull && Boolean.parseBoolean(param.getValue()));
                break;
            case PRIMITIVE_BYTE:
            case BYTE:
                preparedState.setByte(index, isNull ? 0 : Byte.valueOf(param.getValue()));
                break;
            case JAVA_UTIL_DATE:
            case JAVA_SQL_DATE:
                preparedState.setDate(index, isNull ? null : java.sql.Date.valueOf(param.getValue()));
                break;
            case JAVA_SQL_TIME:
                preparedState.setTime(index, isNull ? null : Time.valueOf(param.getValue()));
                break;
            case JAVA_SQL_TIMESTAMP:
                preparedState.setTimestamp(index, isNull ? null : Timestamp.valueOf(param.getValue()));
                break;
            default:
                preparedState.setObject(index, isNull ? null : param.getValue());
        }
    }

    public List<TableMetaWithType> getMetadataV2(String project) throws SQLException, IOException {
        return getMetadataV2(getCubeManager(), project, true);
    }

    protected List<TableMetaWithType> getMetadataV2(CubeManager cubeMgr, String project, boolean cubedOnly) throws SQLException, IOException {
        //Message msg = MsgPicker.getMsg();

        Connection conn = null;
        ResultSet columnMeta = null;
        List<TableMetaWithType> tableMetas = null;
        Map<String, TableMetaWithType> tableMap = null;
        Map<String, ColumnMetaWithType> columnMap = null;
        if (StringUtils.isBlank(project)) {
            return Collections.emptyList();
        }
        ResultSet JDBCTableMeta = null;
        try {
            DataSource dataSource = cacheService.getOLAPDataSource(project);
            conn = dataSource.getConnection();
            DatabaseMetaData metaData = conn.getMetaData();

            JDBCTableMeta = metaData.getTables(null, null, null, null);

            tableMetas = new LinkedList<TableMetaWithType>();
            tableMap = new HashMap<String, TableMetaWithType>();
            columnMap = new HashMap<String, ColumnMetaWithType>();
            while (JDBCTableMeta.next()) {
                String catalogName = JDBCTableMeta.getString(1);
                String schemaName = JDBCTableMeta.getString(2);

                // Not every JDBC data provider offers full 10 columns, e.g., PostgreSQL has only 5
                TableMetaWithType tblMeta = new TableMetaWithType(catalogName == null ? Constant.FakeCatalogName : catalogName, schemaName == null ? Constant.FakeSchemaName : schemaName, JDBCTableMeta.getString(3), JDBCTableMeta.getString(4), JDBCTableMeta.getString(5), null, null, null, null, null);

                if (!cubedOnly || getProjectManager().isExposedTable(project, schemaName + "." + tblMeta.getTABLE_NAME())) {
                    tableMetas.add(tblMeta);
                    tableMap.put(tblMeta.getTABLE_SCHEM() + "#" + tblMeta.getTABLE_NAME(), tblMeta);
                }
            }

            columnMeta = metaData.getColumns(null, null, null, null);

            while (columnMeta.next()) {
                String catalogName = columnMeta.getString(1);
                String schemaName = columnMeta.getString(2);

                // kylin(optiq) is not strictly following JDBC specification
                ColumnMetaWithType colmnMeta = new ColumnMetaWithType(catalogName == null ? Constant.FakeCatalogName : catalogName, schemaName == null ? Constant.FakeSchemaName : schemaName, columnMeta.getString(3), columnMeta.getString(4), columnMeta.getInt(5), columnMeta.getString(6), columnMeta.getInt(7), getInt(columnMeta.getString(8)), columnMeta.getInt(9), columnMeta.getInt(10), columnMeta.getInt(11), columnMeta.getString(12), columnMeta.getString(13), getInt(columnMeta.getString(14)), getInt(columnMeta.getString(15)), columnMeta.getInt(16), columnMeta.getInt(17), columnMeta.getString(18), columnMeta.getString(19), columnMeta.getString(20), columnMeta.getString(21), getShort(columnMeta.getString(22)), columnMeta.getString(23));

                if (!cubedOnly || getProjectManager().isExposedColumn(project, schemaName + "." + colmnMeta.getTABLE_NAME(), colmnMeta.getCOLUMN_NAME())) {
                    tableMap.get(colmnMeta.getTABLE_SCHEM() + "#" + colmnMeta.getTABLE_NAME()).addColumn(colmnMeta);
                    columnMap.put(colmnMeta.getTABLE_SCHEM() + "#" + colmnMeta.getTABLE_NAME() + "#" + colmnMeta.getCOLUMN_NAME(), colmnMeta);
                }
            }

        } finally {
            close(columnMeta, null, conn);
            if (JDBCTableMeta != null) {
                JDBCTableMeta.close();
            }
        }

        ProjectInstance projectInstance = getProjectManager().getProject(project);
        for (String modelName : projectInstance.getModels()) {
            DataModelDesc dataModelDesc = modelServiceV2.listAllModels(modelName, project).get(0);
            if (dataModelDesc.getStatus() == null) {

                // update table type: FACT
                for (TableRef factTable : dataModelDesc.getFactTables()) {
                    String factTableName = factTable.getTableIdentity().replace('.', '#');
                    if (tableMap.containsKey(factTableName)) {
                        tableMap.get(factTableName).getTYPE().add(TableMetaWithType.tableTypeEnum.FACT);
                    } else {
                        // should be used after JDBC exposes all tables and columns
                        // throw new BadRequestException(msg.getTABLE_META_INCONSISTENT());
                    }
                }

                // update table type: LOOKUP
                for (TableRef lookupTable : dataModelDesc.getLookupTables()) {
                    String lookupTableName = lookupTable.getTableIdentity().replace('.', '#');
                    if (tableMap.containsKey(lookupTableName)) {
                        tableMap.get(lookupTableName).getTYPE().add(TableMetaWithType.tableTypeEnum.LOOKUP);
                    } else {
                        // throw new BadRequestException(msg.getTABLE_META_INCONSISTENT());
                    }
                }

                // update column type: PK and FK
                for (JoinTableDesc joinTableDesc : dataModelDesc.getJoinTables()) {
                    JoinDesc joinDesc = joinTableDesc.getJoin();
                    for (String  pk : joinDesc.getPrimaryKey()) {
                        String columnIdentity = (dataModelDesc.findTable(pk.substring(0, pk.indexOf("."))).getTableIdentity() + pk.substring(pk.indexOf("."))).replace('.', '#');
                        if (columnMap.containsKey(columnIdentity)) {
                            columnMap.get(columnIdentity).getTYPE().add(ColumnMetaWithType.columnTypeEnum.PK);
                        } else {
                            // throw new BadRequestException(msg.getCOLUMN_META_INCONSISTENT());
                        }
                    }

                    for (String  fk : joinDesc.getForeignKey()) {
                        String columnIdentity = (dataModelDesc.findTable(fk.substring(0, fk.indexOf("."))).getTableIdentity() + fk.substring(fk.indexOf("."))).replace('.', '#');
                        if (columnMap.containsKey(columnIdentity)) {
                            columnMap.get(columnIdentity).getTYPE().add(ColumnMetaWithType.columnTypeEnum.FK);
                        } else {
                            // throw new BadRequestException(msg.getCOLUMN_META_INCONSISTENT());
                        }
                    }
                }

                // update column type: DIMENSION AND MEASURE
                List<ModelDimensionDesc> dimensions = dataModelDesc.getDimensions();
                for (ModelDimensionDesc dimension : dimensions) {
                    for (String column : dimension.getColumns()) {
                        String columnIdentity = (dataModelDesc.findTable(dimension.getTable()).getTableIdentity() + "." + column).replace('.', '#');
                        if (columnMap.containsKey(columnIdentity)) {
                            columnMap.get(columnIdentity).getTYPE().add(ColumnMetaWithType.columnTypeEnum.DIMENSION);
                        } else {
                            // throw new BadRequestException(msg.getCOLUMN_META_INCONSISTENT());
                        }

                    }
                }

                String[] measures = dataModelDesc.getMetrics();
                for (String measure : measures) {
                    String columnIdentity = (dataModelDesc.findTable(measure.substring(0, measure.indexOf("."))).getTableIdentity() + measure.substring(measure.indexOf("."))).replace('.', '#');
                    if (columnMap.containsKey(columnIdentity)) {
                        columnMap.get(columnIdentity).getTYPE().add(ColumnMetaWithType.columnTypeEnum.MEASURE);
                    } else {
                        // throw new BadRequestException(msg.getCOLUMN_META_INCONSISTENT());
                    }
                }
            }
        }

        return tableMetas;
    }

}
