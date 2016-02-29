/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jumpmind.symmetric.io;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.jumpmind.db.model.Column;
import org.jumpmind.db.model.Table;
import org.jumpmind.db.platform.DatabaseInfo;
import org.jumpmind.db.platform.IDatabasePlatform;
import org.jumpmind.db.platform.db2.Db2DatabasePlatform;
import org.jumpmind.db.sql.DmlStatement;
import org.jumpmind.db.sql.DmlStatement.DmlType;
import org.jumpmind.db.sql.ISqlReadCursor;
import org.jumpmind.db.sql.ISqlRowMapper;
import org.jumpmind.db.sql.Row;
import org.jumpmind.db.util.BinaryEncoding;
import org.jumpmind.symmetric.ISymmetricEngine;
import org.jumpmind.symmetric.common.TableConstants;
import org.jumpmind.symmetric.io.DbCompareReport.TableReport;
import org.jumpmind.symmetric.io.data.transform.TransformColumn;
import org.jumpmind.symmetric.model.Trigger;
import org.jumpmind.symmetric.service.impl.TransformService.TransformTableNodeGroupLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DbCompare has the ability to compare two SQL-based datasources and output a report of
 * of differences, and optionally SQL to bring the target into sync with the source. 
 */
public class DbCompare {

    final Logger log = LoggerFactory.getLogger(getClass());

    ISqlRowMapper<Row> defaultRowMapper = new ISqlRowMapper<Row>() {
        @Override
        public Row mapRow(Row row) {
            return row;
        }
    };   

    private ISymmetricEngine sourceEngine;
    private ISymmetricEngine targetEngine;

    private OutputStream sqlDiffStream;
    private List<String> includedTableNames;
    private List<String> excludedTableNames;
    private boolean useSymmetricConfig = true;
    private DbValueComparator dbValueComparator;

    public DbCompare(ISymmetricEngine sourceEngine, ISymmetricEngine targetEngine) {
        this.sourceEngine = sourceEngine;
        this.targetEngine = targetEngine;
        dbValueComparator = new DbValueComparator(sourceEngine, targetEngine);
    }

    public DbCompareReport compare() {
        DbCompareReport report = new DbCompareReport();
        List<DbCompareTables> tablesToCompare = getTablesToCompare();
        for (DbCompareTables tables : tablesToCompare) {
            TableReport tableReport = compareTables(tables);
            report.addTableReport(tableReport);
        }

        return report;
    }

    protected TableReport compareTables(DbCompareTables tables) {
        String sourceSelect = getComparisonSQL(tables.getSourceTable(), sourceEngine.getDatabasePlatform());
        String targetSelect = getComparisonSQL(tables.getTargetTable(), targetEngine.getDatabasePlatform());

        CountingSqlReadCursor sourceCursor = new CountingSqlReadCursor(sourceEngine.getDatabasePlatform().
                getSqlTemplate().queryForCursor(sourceSelect, defaultRowMapper));
        CountingSqlReadCursor targetCursor = new CountingSqlReadCursor(targetEngine.getDatabasePlatform().
                getSqlTemplate().queryForCursor(targetSelect, defaultRowMapper));

        TableReport tableReport = new TableReport();
        tableReport.setSourceTable(tables.getSourceTable().getFullyQualifiedTableName());
        tableReport.setTargetTable(tables.getTargetTable().getFullyQualifiedTableName());

        Row sourceRow = sourceCursor.next();
        Row targetRow = targetCursor.next();

        int counter = 0;
        long startTime = System.currentTimeMillis();

        while (true) {  
            if (sourceRow == null && targetRow == null) {
                break;
            }

            counter++;
            if ((counter % 10000) == 0) {
                log.info("{} rows processed for table {}. Elapsed time {}.", 
                        counter, tables.getSourceTable().getName(), (System.currentTimeMillis()-startTime));
            }

            DbCompareRow sourceCompareRow = sourceRow != null ? 
                    new DbCompareRow(sourceEngine, dbValueComparator, tables.getSourceTable(), sourceRow) : null;
            DbCompareRow targetCompareRow = targetRow != null ? 
                new DbCompareRow(targetEngine, dbValueComparator,  tables.getTargetTable(), targetRow) : null;

//                System.out.println("Source: " + sourceCompareRow.getRowPkValues() + " -> " + targetCompareRow.getRowPkValues());
                
                int comparePk = comparePk(tables, sourceCompareRow, targetCompareRow);
                if (comparePk == 0) {
                    Map<Column, String> deltas = sourceCompareRow.compareTo(tables, targetCompareRow);
                    if (deltas.isEmpty()) {
                        tableReport.countMatchedRow();                    
                    } else {
                        writeUpdate(targetCompareRow, deltas);
                        tableReport.countDifferentRow();
                    }

                    sourceRow = sourceCursor.next();
                    targetRow = targetCursor.next();
                } else if (comparePk < 0) {
                    writeInsert(sourceCompareRow,  tables);
                    tableReport.countMissingRow();
                    sourceRow = sourceCursor.next();
                } else {
                    writeDelete(targetCompareRow);
                    tableReport.countExtraRow();
                    targetRow = targetCursor.next();
                }
        }

        tableReport.setSourceRows(sourceCursor.count);
        tableReport.setTargetRows(targetCursor.count);

        return tableReport;
    }
    protected void writeDelete(DbCompareRow targetCompareRow) {
        if (sqlDiffStream == null) {
            return;
        }

        Table table = targetCompareRow.getTable();

        DmlStatement statement =  targetEngine.getDatabasePlatform().createDmlStatement(DmlType.DELETE,
                table.getCatalog(), table.getSchema(), table.getName(),
                table.getPrimaryKeyColumns(), null,
                null, null);

        Row row = new Row(targetCompareRow.getTable().getPrimaryKeyColumnCount());

        for (int i = 0; i < targetCompareRow.getTable().getPrimaryKeyColumnCount(); i++) {
            row.put(table.getColumn(i).getName(), 
                    targetCompareRow.getRowValues().get(targetCompareRow.getTable().getColumn(i).getName()));
        }

        String sql = statement.buildDynamicDeleteSql(BinaryEncoding.HEX, row, false, true);

        writeStatement(sql);
    }

    protected void writeInsert(DbCompareRow sourceCompareRow, DbCompareTables tables) { 
        if (sqlDiffStream == null) {
            return;
        }
        
        Table targetTable = tables.getTargetTable();

        DmlStatement statement =  targetEngine.getDatabasePlatform().createDmlStatement(DmlType.INSERT,
                targetTable.getCatalog(), targetTable.getSchema(), targetTable.getName(),
                targetTable.getPrimaryKeyColumns(), targetTable.getColumns(),
                null, null);

        Row row = new Row(targetTable.getColumnCount());

        for (Column sourceColumn : tables.getSourceTable().getColumns()) {
            Column targetColumn = tables.getColumnMapping().get(sourceColumn);
            if (targetColumn == null) {
                continue;
            }
            
            row.put(targetColumn.getName(), sourceCompareRow.getRowValues().
                    get(sourceColumn.getName()));
        }
        
        String sql = statement.buildDynamicSql(BinaryEncoding.HEX, row, false, false);

        writeStatement(sql);
    }

    protected void writeUpdate(DbCompareRow targetCompareRow, Map<Column, String> deltas) { 
        if (sqlDiffStream == null) {
            return;
        }

        Table table = targetCompareRow.getTable();

        Column[] changedColumns = deltas.keySet().toArray(new Column[deltas.keySet().size()]);

        DmlStatement statement = targetEngine.getDatabasePlatform().createDmlStatement(DmlType.UPDATE,
                table.getCatalog(), table.getSchema(), table.getName(),
                table.getPrimaryKeyColumns(), changedColumns,
                null, null);

        Row row = new Row(changedColumns.length+table.getPrimaryKeyColumnCount());
        for (Column changedColumn : deltas.keySet()) {
            String value = deltas.get(changedColumn);
            row.put(changedColumn.getName(), value);
        }
        for (String pkColumnName : table.getPrimaryKeyColumnNames()) {
            String value = targetCompareRow.getRow().getString(pkColumnName);
            row.put(pkColumnName, value);
        }
        String sql = statement.buildDynamicSql(BinaryEncoding.HEX, row, false, true);

        writeStatement(sql);
    }

    protected void writeStatement(String statement) {
        try {
            sqlDiffStream.write(statement.getBytes()); 
            sqlDiffStream.write("\r\n".getBytes());
        } catch (Exception ex) {
            throw new RuntimeException("failed to write to sqlDiffStream.", ex);
        }
    }

    protected int comparePk(DbCompareTables tables, DbCompareRow sourceCompareRow, DbCompareRow targetCompareRow) {
        if (sourceCompareRow != null && targetCompareRow == null) {
            return -1;
        }
        if (sourceCompareRow == null && targetCompareRow != null) {
            return 1;
        }

        return sourceCompareRow.comparePks(tables, targetCompareRow);
    }

    protected String getComparisonSQL(Table table, IDatabasePlatform platform) {
        DmlStatement statement = platform.createDmlStatement(DmlType.SELECT,
                table.getCatalog(), table.getSchema(), table.getName(),
                null, table.getColumns(),
                null, null);

        StringBuilder sql = new StringBuilder(statement.getSql());
        sql.append("1=1 ");

        sql.append(buildOrderBy(table, platform));
        log.info("Comparison SQL: {}", sql);
        return sql.toString();
    }

    protected String buildOrderBy(Table table, IDatabasePlatform platform) {
        DatabaseInfo databaseInfo = platform.getDatabaseInfo();
        String quote = databaseInfo.getDelimiterToken() == null ? "" : databaseInfo.getDelimiterToken(); 
        StringBuilder orderByClause = new StringBuilder("ORDER BY ");
        for (Column pkColumn : table.getPrimaryKeyColumns()) {
            String columnName = new StringBuilder(quote).append(pkColumn.getName()).append(quote).toString();
            if (platform instanceof Db2DatabasePlatform && pkColumn.isOfTextType() ) {
                orderByClause.append("TRANSLATE ")
                        .append("(").append(columnName).append(", 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789',")
                        .append("'0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz')");
            } else {
                orderByClause.append(columnName);
            }
            orderByClause.append(",");
        }
        orderByClause.setLength(orderByClause.length()-1);
        return orderByClause.toString();       
    }

    protected List<DbCompareTables> getTablesToCompare() {
        List<DbCompareTables> tablesToCompare;
        if (useSymmetricConfig) {
            tablesToCompare = loadTablesFromConfig();
        } else {
            tablesToCompare = loadTablesFromArguments();
        }

        return tablesToCompare;
    }


    protected  List<DbCompareTables> loadTablesFromConfig() {        
        List<Trigger> triggers = sourceEngine.getTriggerRouterService().getTriggersForCurrentNode(true);
        List<String> configTables = TableConstants.getConfigTables(sourceEngine.getTablePrefix());

        List<String> tableNames = new ArrayList<String>();

        for (Trigger trigger : triggers) {
            if (!configTables.contains(trigger.getFullyQualifiedSourceTableName())) {
                tableNames.add(trigger.getFullyQualifiedSourceTableName());
            }
        }

        return loadTables(tableNames);
    }

    protected List<DbCompareTables> loadTables(List<String> tableNames) {
        List<DbCompareTables> tablesFromConfig = new ArrayList<DbCompareTables>();
        
        List<String> filteredTablesNames = filterTables(tableNames);

        for (String tableName : filteredTablesNames) {
            Table sourceTable = sourceEngine.getDatabasePlatform().getTableFromCache(tableName, true);
            if (sourceTable == null) {
                log.warn("No source table found for table name {}", tableName);
                continue;
            }
            if (sourceTable.getPrimaryKeyColumnCount() == 0) {
                log.warn("Source table {} doesn't have any primary key columns and will not be considered in the comparison.", sourceTable);
                continue;                
            }

            DbCompareTables tables = new DbCompareTables(sourceTable, null);

            Table targetTable = loadTargetTable(tables);
            if (targetTable == null) {
                log.warn("No target table found for table {}", tableName);
                continue;
            } else if (targetTable.getPrimaryKeyColumnCount() == 0) {
                log.warn("Target table {} doesn't have any primary key columns and will not be considered in the comparison.", targetTable);
                continue;                
            }          

            tables.applyColumnMappings();
            tablesFromConfig.add(tables);
        }

        return tablesFromConfig;        
    }

    protected Table loadTargetTable(DbCompareTables tables) {
        Table targetTable = null;
        if (useSymmetricConfig) {
            TransformTableNodeGroupLink transform = getTransformFor(tables.getSourceTable());
            if (transform != null) {
                targetTable =  loadTargetTableUsingTransform(transform);
                tables.setTargetTable(targetTable);
                tables.setTransform(transform);
                return targetTable;
            }
        } 

        targetTable = targetEngine.getDatabasePlatform().
                getTableFromCache(tables.getSourceTable().getName(), true);
        tables.setTargetTable(targetTable);

        return targetTable;
    }

    protected TransformTableNodeGroupLink getTransformFor(Table sourceTable) {
        String sourceNodeGroupId = sourceEngine.getNodeService().findIdentity().getNodeGroupId(); 
        String targetNodeGroupId = targetEngine.getNodeService().findIdentity().getNodeGroupId(); 
        List<TransformTableNodeGroupLink> transforms = 
                sourceEngine.getTransformService().findTransformsFor(
                        sourceNodeGroupId, targetNodeGroupId, sourceTable.getName());
        if (!CollectionUtils.isEmpty(transforms)) {
            TransformTableNodeGroupLink transform = transforms.get(0); // Only can operate on a single table transform for now.
            if (!StringUtils.isEmpty(transform.getFullyQualifiedTargetTableName())) {
                return transform;
            }
        }
        return null;
    }

    protected Table loadTargetTableUsingTransform(TransformTableNodeGroupLink transform) {
        Table targetTable = targetEngine.getDatabasePlatform().	
                getTableFromCache(transform.getTargetCatalogName(), transform.getTargetSchemaName(), transform.getTargetTableName(), true); 

        return targetTable;
    }

    protected Table cloneTable(Table table) {
        try {
            return (Table) table.clone();
        } catch (CloneNotSupportedException ex) {
            throw new RuntimeException(ex);
        }        
    }

    protected List<DbCompareTables> loadTablesFromArguments() {
        if (CollectionUtils.isEmpty(includedTableNames)) {
            throw new RuntimeException("includedTableNames not provided,  includedTableNames must be provided "
                    + "when not comparing using SymmetricDS config.");
        }

        return loadTables(includedTableNames);
    }

    protected List<String> filterTables(List<String> tables) {        
        List<String> filteredTables = new ArrayList<String>(tables.size());

        if (!CollectionUtils.isEmpty(includedTableNames)) {
            for (String includedTableName : includedTableNames) {                
                for (String tableName : tables) {
                    if (StringUtils.equalsIgnoreCase(tableName.trim(), includedTableName.trim())) {
                        filteredTables.add(tableName);
                    }
                }
            }
        } else {
            filteredTables.addAll(tables);
        }

        if (!CollectionUtils.isEmpty(excludedTableNames)) {
            List<String> excludedTables = new ArrayList<String>(filteredTables);

            for (String excludedTableName : excludedTableNames) {            
                    for (String tableName : filteredTables) {
                        if (StringUtils.equalsIgnoreCase(tableName.trim(), excludedTableName.trim())) {
                            excludedTables.remove(tableName);
                        }
                }
            }
            return excludedTables;
        }        

        return filteredTables;
    }

    public OutputStream getSqlDiffStream() {
        return sqlDiffStream;
    }

    public void setSqlDiffStream(OutputStream sqlDiffStream) {
        this.sqlDiffStream = sqlDiffStream;
    }

    public List<String> getIncludedTableNames() {
        return includedTableNames;
    }

    public void setIncludedTableNames(List<String> includedTableNames) {
        this.includedTableNames = includedTableNames;
    }

    public List<String> getExcludedTableNames() {
        return excludedTableNames;
    }

    public void setExcludedTableNames(List<String> excludedTableNames) {
        this.excludedTableNames = excludedTableNames;
    }

    public boolean isUseSymmetricConfig() {
        return useSymmetricConfig;
    }

    public void setUseSymmetricConfig(boolean useSymmetricConfig) {
        this.useSymmetricConfig = useSymmetricConfig;
    }

    class CountingSqlReadCursor implements ISqlReadCursor<Row> {

        ISqlReadCursor<Row> wrapped;
        int count = 0;

        CountingSqlReadCursor(ISqlReadCursor<Row> wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public Row next() {
            Row row = wrapped.next();
            if (row != null) {
                count++;
            }
            return row;
        }

        @Override
        public void close() {
            wrapped.close();
        }
    }
}