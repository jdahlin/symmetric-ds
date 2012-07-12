/*
 * Licensed to JumpMind Inc under one or more contributor 
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding 
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU Lesser General Public License (the
 * "License"); you may not use this file except in compliance
 * with the License. 
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see           
 * <http://www.gnu.org/licenses/>.
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License. 
 */
package org.jumpmind.symmetric.db;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.jumpmind.db.io.DatabaseIO;
import org.jumpmind.db.model.Column;
import org.jumpmind.db.model.Database;
import org.jumpmind.db.model.ForeignKey;
import org.jumpmind.db.model.IIndex;
import org.jumpmind.db.model.Reference;
import org.jumpmind.db.model.Table;
import org.jumpmind.db.platform.IDatabasePlatform;
import org.jumpmind.db.platform.IDdlBuilder;
import org.jumpmind.db.sql.ISqlTemplate;
import org.jumpmind.db.sql.ISqlTransaction;
import org.jumpmind.db.sql.SqlConstants;
import org.jumpmind.db.sql.SqlException;
import org.jumpmind.db.sql.SqlScript;
import org.jumpmind.db.util.BinaryEncoding;
import org.jumpmind.exception.IoException;
import org.jumpmind.symmetric.common.Constants;
import org.jumpmind.symmetric.common.ParameterConstants;
import org.jumpmind.symmetric.ext.IDatabaseUpgradeListener;
import org.jumpmind.symmetric.io.data.DataEventType;
import org.jumpmind.symmetric.model.Channel;
import org.jumpmind.symmetric.model.Node;
import org.jumpmind.symmetric.model.Trigger;
import org.jumpmind.symmetric.model.TriggerHistory;
import org.jumpmind.symmetric.model.TriggerRouter;
import org.jumpmind.symmetric.service.IParameterService;
import org.jumpmind.symmetric.util.AppUtils;
import org.jumpmind.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * The abstract class for database dialects.
 */
abstract public class AbstractSymmetricDialect implements ISymmetricDialect {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    public static final int MAX_SYMMETRIC_SUPPORTED_TRIGGER_SIZE = 50;

    protected IDatabasePlatform platform;

    protected AbstractTriggerTemplate triggerTemplate;

    protected IParameterService parameterService;

    protected Boolean supportsGetGeneratedKeys;

    protected String databaseName;

    protected String driverVersion;

    protected String driverName;

    protected int databaseMajorVersion;

    protected int databaseMinorVersion;

    protected String databaseProductVersion;

    protected Set<String> sqlKeywords;

    protected boolean supportsTransactionViews = false;

    protected List<IDatabaseUpgradeListener> databaseUpgradeListeners = new ArrayList<IDatabaseUpgradeListener>();

    public AbstractSymmetricDialect(IParameterService parameterService, IDatabasePlatform platform) {
        this.parameterService = parameterService;
        this.platform = platform;

        log.info("The DbDialect being used is {}", this.getClass().getName());

        ISqlTemplate sqlTemplate = this.platform.getSqlTemplate();
        this.databaseMajorVersion = sqlTemplate.getDatabaseMajorVersion();
        this.databaseMinorVersion = sqlTemplate.getDatabaseMinorVersion();
        this.databaseName = sqlTemplate.getDatabaseProductName();
        this.databaseProductVersion = sqlTemplate.getDatabaseProductVersion();
        this.driverName = sqlTemplate.getDriverName();
        this.driverVersion = sqlTemplate.getDriverVersion();
    }

    public String toFormattedTimestamp(java.util.Date time) {
        StringBuilder ts = new StringBuilder("{ts '");
        ts.append(SqlConstants.JDBC_TIMESTAMP_FORMATTER.format(time));
        ts.append("'}");
        return ts.toString();
    }

    public boolean requiresAutoCommitFalseToSetFetchSize() {
        return false;
    }

    /*
     * Provide a default implementation of this method using DDLUtils,
     * getMaxColumnNameLength()
     */
    public int getMaxTriggerNameLength() {
        int max = getPlatform().getDatabaseInfo().getMaxColumnNameLength();
        return max < MAX_SYMMETRIC_SUPPORTED_TRIGGER_SIZE && max > 0 ? max
                : MAX_SYMMETRIC_SUPPORTED_TRIGGER_SIZE;
    }

    protected void initTablesAndFunctionsForSpecificDialect() {
    }

    public void initTablesAndFunctions() {
        initTablesAndFunctionsForSpecificDialect();
        createOrAlterTablesIfNecessary();
        createRequiredFunctions();
        platform.resetCachedTableModel();
    }

    final public boolean doesTriggerExist(String catalogName, String schema, String tableName,
            String triggerName) {
        try {
            return doesTriggerExistOnPlatform(catalogName, schema, tableName, triggerName);
        } catch (Exception ex) {
            log.warn("Could not figure out if the trigger exists.  Assuming that is does not", ex);
            return false;
        }
    }

    protected void createRequiredFunctions() {
        String[] functions = triggerTemplate.getFunctionsToInstall();
        for (int i = 0; i < functions.length; i++) {
            String funcName = this.parameterService.getTablePrefix() + "_" + functions[i];
            if (this.platform.getSqlTemplate().queryForInt(
                    triggerTemplate.getFunctionInstalledSql(funcName, platform.getDefaultSchema())) == 0) {
                this.platform.getSqlTemplate().update(
                        triggerTemplate.getFunctionSql(functions[i], funcName));
                log.info("Just installed {}", funcName);
            }
        }
    }

    abstract public BinaryEncoding getBinaryEncoding();

    abstract protected boolean doesTriggerExistOnPlatform(String catalogName, String schema,
            String tableName, String triggerName);

    public String getTransactionTriggerExpression(String defaultCatalog, String defaultSchema,
            Trigger trigger) {
        return "null";
    }

    public String createInitialLoadSqlFor(Node node, TriggerRouter trigger, Table table,
            TriggerHistory triggerHistory, Channel channel) {
        return triggerTemplate.createInitalLoadSql(node, trigger, table, triggerHistory, channel)
                .trim();
    }

    public String createPurgeSqlFor(Node node, TriggerRouter triggerRouter) {
        return String.format(
                parameterService.getString(ParameterConstants.INITIAL_LOAD_DELETE_FIRST_SQL),
                triggerRouter.qualifiedTargetTableName());
    }

    public String createCsvDataSql(Trigger trigger, TriggerHistory triggerHistory, Channel channel,
            String whereClause) {
        return triggerTemplate.createCsvDataSql(
                trigger,
                triggerHistory,
                platform.getTableFromCache(trigger.getSourceCatalogName(),
                        trigger.getSourceSchemaName(), trigger.getSourceTableName(), false),
                channel, whereClause).trim();
    }

    public String createCsvPrimaryKeySql(Trigger trigger, TriggerHistory triggerHistory,
            Channel channel, String whereClause) {
        return triggerTemplate.createCsvPrimaryKeySql(
                trigger,
                triggerHistory,
                platform.getTableFromCache(trigger.getSourceCatalogName(),
                        trigger.getSourceSchemaName(), trigger.getSourceTableName(), false),
                channel, whereClause).trim();
    }

    public Set<String> getSqlKeywords() {
        if (sqlKeywords == null) {
            this.sqlKeywords = this.platform.getSqlTemplate().getSqlKeywords();
        }
        return sqlKeywords;
    }

    public void removeTrigger(StringBuilder sqlBuffer, String catalogName, String schemaName,
            String triggerName, String tableName, TriggerHistory oldHistory) {
        schemaName = schemaName == null ? "" : (schemaName + ".");
        final String sql = "drop trigger " + schemaName + triggerName;
        logSql(sql, sqlBuffer);
        if (parameterService.is(ParameterConstants.AUTO_SYNC_TRIGGERS)) {
            try {
                this.platform.getSqlTemplate().update(sql);
            } catch (Exception e) {
                log.warn("Trigger does not exist");
            }
        }
    }

    final protected void logSql(String sql, StringBuilder sqlBuffer) {
        if (sqlBuffer != null && StringUtils.isNotBlank(sql)) {
            sqlBuffer.append(sql);
            sqlBuffer.append(System.getProperty("line.separator"));
            sqlBuffer.append(System.getProperty("line.separator"));
        }
    }

    /*
     * Create the configured trigger. The catalog will be changed to the source
     * schema if the source schema is configured.
     */
    public void createTrigger(final StringBuilder sqlBuffer, final DataEventType dml,
            final Trigger trigger, final TriggerHistory hist, final Channel channel,
            final String tablePrefix, final Table table) {
        log.info("Creating {} trigger for {}", hist.getTriggerNameForDmlType(dml),
                table.getFullyQualifiedTableName());

        String previousCatalog = null;
        String sourceCatalogName = trigger.getSourceCatalogName();
        String defaultCatalog = platform.getDefaultCatalog();
        String defaultSchema = platform.getDefaultSchema();

        String triggerSql = triggerTemplate.createTriggerDDL(dml, trigger, hist, channel,
                tablePrefix, table, defaultCatalog, defaultSchema);

        String postTriggerDml = createPostTriggerDDL(dml, trigger, hist, channel, tablePrefix,
                table);

        if (parameterService.is(ParameterConstants.AUTO_SYNC_TRIGGERS)) {
            ISqlTransaction transaction = null;
            try {
                transaction = this.platform.getSqlTemplate().startSqlTransaction();
                previousCatalog = switchCatalogForTriggerInstall(sourceCatalogName, transaction);

                try {
                    log.debug("Running: {}", triggerSql);
                    transaction.execute(triggerSql);
                } catch (SqlException ex) {
                    log.error("Failed to create trigger: {}", triggerSql);
                    throw ex;
                }

                if (StringUtils.isNotBlank(postTriggerDml)) {
                    try {
                        transaction.execute(postTriggerDml);
                    } catch (SqlException ex) {
                        log.error("Failed to create post trigger: {}", postTriggerDml);
                        throw ex;
                    }
                }
                transaction.commit();
            } catch (SqlException ex) {
                transaction.rollback();
                throw ex;
            } finally {
                try {
                    if (sourceCatalogName != null
                            && !sourceCatalogName.equalsIgnoreCase(previousCatalog)) {
                        switchCatalogForTriggerInstall(previousCatalog, transaction);
                    }
                } finally {
                    transaction.close();
                }

            }
        }

        logSql(triggerSql, sqlBuffer);
        logSql(postTriggerDml, sqlBuffer);

    }

    /*
     * Provide the option switch a connection's schema for trigger installation.
     */
    protected String switchCatalogForTriggerInstall(String catalog, ISqlTransaction transaction) {
        return null;
    }

    protected String createPostTriggerDDL(DataEventType dml, Trigger trigger, TriggerHistory hist,
            Channel channel, String tablePrefix, Table table) {
        return triggerTemplate.createPostTriggerDDL(dml, trigger, hist, channel, tablePrefix,
                table, platform.getDefaultCatalog(), platform.getDefaultSchema());
    }

    public String getCreateSymmetricDDL() {
        Database database = readSymmetricSchemaFromXml();
        prefixConfigDatabase(database);
        IDdlBuilder builder = platform.getDdlBuilder();
        return builder.createTables(database, true);
    }

    public String getCreateTableSQL(TriggerRouter triggerRouter) {
        Table table = platform.getTableFromCache(null, triggerRouter.getTrigger()
                .getSourceSchemaName(), triggerRouter.getTrigger().getSourceTableName(), false);
        return platform.getDdlBuilder().createTable(table);
    }

    private void setDatabaseName(TriggerRouter triggerRouter, Database db) {
        db.setName(triggerRouter.getTargetSchema(platform.getDefaultSchema()));
        if (db.getName() == null) {
            db.setName(platform.getDefaultCatalog());
        }
        if (db.getName() == null) {
            db.setName("DDL");
        }
    }

    public String getCreateTableXML(TriggerRouter triggerRouter) {
        Table table = getTable(triggerRouter.getTrigger(), true);
        String targetTableName = triggerRouter.getRouter().getTargetTableName();
        if (StringUtils.isNotBlank(targetTableName)) {
            table.setName(targetTableName);
        }
        Database db = new Database();
        setDatabaseName(triggerRouter, db);
        db.addTable(table);
        StringWriter buffer = new StringWriter();
        DatabaseIO xmlWriter = new DatabaseIO();
        xmlWriter.write(db, buffer);
        // TODO: remove when these bugs are fixed in DdlUtils
        String xml = buffer.toString().replaceAll("&apos;", "");
        xml = xml.replaceAll("default=\"empty_blob\\(\\) *\"", "");
        xml = xml.replaceAll("unique name=\"PRIMARY\"", "unique name=\"PRIMARYINDEX\"");
        // on postgres, this is a "text" column
        xml = xml.replaceAll("type=\"VARCHAR\" size=\"2147483647\"", "type=\"LONGVARCHAR\"");
        xml = xml.replaceAll("type=\"BINARY\" size=\"2147483647\"", "type=\"LONGVARBINARY\"");
        return xml;
    }

    protected void prefixConfigDatabase(Database targetTables) {
        try {
            String prefix = parameterService.getTablePrefix()
                    + (StringUtils.isNotBlank(parameterService.getTablePrefix()) ? "_" : "");

            Table[] tables = targetTables.getTables();

            boolean storesUpperCaseIdentifiers = platform.isStoresUpperCaseIdentifiers();
            for (Table table : tables) {
                String name = String.format("%s%s", prefix, table.getName());
                table.setName(storesUpperCaseIdentifiers ? name.toUpperCase() : name.toLowerCase());
                fixForeignKeys(table, prefix, storesUpperCaseIdentifiers);
                fixIndexes(table, prefix, storesUpperCaseIdentifiers);
                fixColumnNames(table, storesUpperCaseIdentifiers);
            }

        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    public Table getTable(Trigger trigger, boolean useCache) {
        return platform.getTableFromCache(trigger.getSourceCatalogName(),
                trigger.getSourceSchemaName(), trigger.getSourceTableName(), !useCache);
    }

    /*
     * @return true if SQL was executed.
     */
    public boolean createOrAlterTablesIfNecessary() {

        Database modelFromXml = readSymmetricSchemaFromXml();

        String extraTablesXml = parameterService
                .getString(ParameterConstants.AUTO_CONFIGURE_EXTRA_TABLES);
        if (StringUtils.isNotBlank(extraTablesXml)) {
            try {
                modelFromXml = merge(modelFromXml, readDatabaseFromXml(extraTablesXml));
            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
            }
        }

        try {
            log.info("Checking if SymmetricDS tables need created or altered");
            Database modelFromDatabase = new Database();

            Table[] tablesFromXml = modelFromXml.getTables();
            for (Table tableFromXml : tablesFromXml) {
                Table tableFromDatabase = platform.getTableFromCache(platform.getDefaultCatalog(),
                        platform.getDefaultSchema(), tableFromXml.getName(), true);
                if (tableFromDatabase != null) {
                    modelFromDatabase.addTable(tableFromDatabase);
                }
            }

            IDdlBuilder builder = platform.getDdlBuilder();

            if (builder.isAlterDatabase(modelFromDatabase, modelFromXml)) {
                log.info("There are SymmetricDS tables that needed altered");
                String delimiter = platform.getDatabaseInfo().getSqlCommandDelimiter();

                for (IDatabaseUpgradeListener listener : databaseUpgradeListeners) {
                    String sql = listener
                            .beforeUpgrade(this, this.parameterService.getTablePrefix(),
                                    modelFromDatabase, modelFromXml);
                    new SqlScript(sql, getPlatform().getSqlTemplate(), true, delimiter, null)
                            .execute(platform.getDatabaseInfo().isRequiresAutoCommitForDdl());
                }

                String alterSql = builder.alterDatabase(modelFromDatabase, modelFromXml);

                log.info("Alter SQL Generated: {}", alterSql);

                new SqlScript(alterSql, getPlatform().getSqlTemplate(), true, delimiter, null)
                        .execute(platform.getDatabaseInfo().isRequiresAutoCommitForDdl());

                for (IDatabaseUpgradeListener listener : databaseUpgradeListeners) {
                    String sql = listener.afterUpgrade(this,
                            this.parameterService.getTablePrefix(), modelFromXml);
                    new SqlScript(sql, getPlatform().getSqlTemplate(), true, delimiter, null)
                            .execute(platform.getDatabaseInfo().isRequiresAutoCommitForDdl());
                }

                log.info("Done with auto update of SymmetricDS tables");
                return true;
            } else {
                return false;
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public Database readSymmetricSchemaFromXml() {
        try {
            Database database = merge(readDatabaseFromXml("/symmetric-schema.xml"),
                    readDatabaseFromXml("/console-schema.xml"));
            prefixConfigDatabase(database);
            return database;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    protected Database readDatabaseFromXml(String resourceName) throws IOException {
        try {
            return platform.readDatabaseFromXml(resourceName, true);
        } catch (IoException ex) {
            return new Database();
        }
    }

    protected Database merge(Database... databases) {
        Database database = new Database();
        if (databases != null) {
            for (Database db : databases) {
                Table[] tables = db.getTables();
                for (Table table : tables) {
                    database.addTable(table);
                }
            }
        }
        return database;
    }

    protected void fixColumnNames(Table table, boolean storesUpperCaseIdentifiers) {
        Column[] columns = table.getColumns();
        for (Column column : columns) {
            column.setName(storesUpperCaseIdentifiers ? column.getName().toUpperCase() : column
                    .getName().toLowerCase());
        }
    }

    protected void fixForeignKeys(Table table, String tablePrefix,
            boolean storesUpperCaseIdentifiers) throws CloneNotSupportedException {
        ForeignKey[] keys = table.getForeignKeys();
        for (ForeignKey key : keys) {
            String prefixedName = tablePrefix + key.getForeignTableName();
            prefixedName = storesUpperCaseIdentifiers ? prefixedName.toUpperCase() : prefixedName
                    .toLowerCase();
            key.setForeignTableName(prefixedName);

            String keyName = tablePrefix + key.getName();
            keyName = storesUpperCaseIdentifiers ? keyName.toUpperCase() : keyName.toLowerCase();
            key.setName(keyName);

            Reference[] refs = key.getReferences();
            for (Reference reference : refs) {
                reference.setForeignColumnName(storesUpperCaseIdentifiers ? reference
                        .getForeignColumnName().toUpperCase() : reference.getForeignColumnName()
                        .toLowerCase());
                reference.setLocalColumnName(storesUpperCaseIdentifiers ? reference
                        .getLocalColumnName().toUpperCase() : reference.getLocalColumnName()
                        .toLowerCase());
            }
        }
    }

    protected void fixIndexes(Table table, String tablePrefix, boolean storesUpperCaseIdentifiers)
            throws CloneNotSupportedException {
        IIndex[] indexes = table.getIndices();
        if (indexes != null) {
            for (IIndex index : indexes) {
                String prefixedName = tablePrefix + index.getName();
                prefixedName = storesUpperCaseIdentifiers ? prefixedName.toUpperCase()
                        : prefixedName.toLowerCase();
                index.setName(prefixedName);
            }
        }
    }

    public IDatabasePlatform getPlatform() {
        return this.platform;
    }

    public String getName() {
        return databaseName;
    }

    public String getVersion() {
        return databaseMajorVersion + "." + databaseMinorVersion;
    }

    public int getMajorVersion() {
        return databaseMajorVersion;
    }

    public int getMinorVersion() {
        return databaseMinorVersion;
    }

    public String getProductVersion() {
        return databaseProductVersion;
    }

    public boolean supportsTransactionViews() {
        return supportsTransactionViews;
    }

    public long insertWithGeneratedKey(String sql, SequenceIdentifier sequenceId) {
        return insertWithGeneratedKey(sql, sequenceId, null, null);
    }

    public long insertWithGeneratedKey(final String sql, final SequenceIdentifier identifier,
            Object... args) {
        return platform.getSqlTemplate().insertWithGeneratedKey(sql,
                getSequenceKeyName(identifier), getSequenceKeyName(identifier), args, null);
    }

    public String getSequenceName(SequenceIdentifier identifier) {
        switch (identifier) {
            case DATA:
                return "sym_data_data_id";
            case TRIGGER_HIST:
                return "sym_trigger_his_ger_hist_id";
        }
        return null;
    }

    public String getSequenceKeyName(SequenceIdentifier identifier) {
        switch (identifier) {
            case DATA:
                return "data_id";
            case TRIGGER_HIST:
                return "trigger_hist_id";
        }
        return null;
    }

    @Deprecated
    public Column[] orderColumns(String[] columnNames, Table table) {
        Column[] unorderedColumns = table.getColumns();
        Column[] orderedColumns = new Column[columnNames.length];
        for (int i = 0; i < columnNames.length; i++) {
            String name = columnNames[i];
            for (Column column : unorderedColumns) {
                if (column.getName().equalsIgnoreCase(name)) {
                    orderedColumns[i] = column;
                    break;
                }
            }
        }
        return orderedColumns;
    }

    public void disableSyncTriggers(ISqlTransaction transaction) {
        disableSyncTriggers(transaction, null);
    }

    public boolean supportsTransactionId() {
        return false;
    }

    public boolean isBlobSyncSupported() {
        return true;
    }

    public boolean isClobSyncSupported() {
        return true;
    }

    public boolean isTransactionIdOverrideSupported() {
        return true;
    }

    public String getEngineName() {
        return parameterService.getString(ParameterConstants.ENGINE_NAME);
    }

    public boolean supportsOpenCursorsAcrossCommit() {
        return true;
    }

    public String getInitialLoadTableAlias() {
        return "t";
    }

    public String preProcessTriggerSqlClause(String sqlClause) {
        return sqlClause;
    }

    public int getRouterDataPeekAheadCount() {
        return parameterService.getInt(ParameterConstants.ROUTING_PEEK_AHEAD_WINDOW);
    }

    public void truncateTable(String tableName) {
        String quote = platform.getDdlBuilder().isDelimitedIdentifierModeOn() ? platform
                .getDatabaseInfo().getDelimiterToken() : "";
        boolean success = false;
        int tryCount = 5;
        while (!success && tryCount > 0) {
            try {
                Table table = platform.getTableFromCache(tableName, false);
                if (table != null) {
                    platform.getSqlTemplate().update(
                            String.format("truncate table %s%s%s", quote, table.getName(), quote));
                    success = true;
                } else {
                    throw new RuntimeException(String.format("Could not find %s to trunate",
                            tableName));
                }
            } catch (SqlException ex) {
                log.warn(ex.getMessage(), ex);
                AppUtils.sleep(5000);
                tryCount--;
            }
        }
    }

    public boolean areDatabaseTransactionsPendingSince(long time) {
        throw new UnsupportedOperationException();
    }

    public long getDatabaseTime() {
        try {
            String sql = "select current_timestamp from " + this.parameterService.getTablePrefix()
                    + "_node_identity";
            sql = FormatUtils.replaceTokens(sql, platform.getSqlScriptReplacementTokens(), false);
            Date dateTime = this.platform.getSqlTemplate()
                    .queryForObject(sql, java.util.Date.class);
            if (dateTime != null) {
                return dateTime.getTime();
            } else {
                return System.currentTimeMillis();
            }
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            return System.currentTimeMillis();
        }
    }

    public String getSourceNodeExpression() {
        return null;
    }

    final public String getDataHasChangedCondition(Trigger trigger) {
        if (parameterService.is(ParameterConstants.TRIGGER_UPDATE_CAPTURE_CHANGED_DATA_ONLY)) {
            return getDbSpecificDataHasChangedCondition(trigger);
        } else {
            return Constants.ALWAYS_TRUE_CONDITION;
        }
    }

    protected String getDbSpecificDataHasChangedCondition(Trigger trigger) {
        return Constants.ALWAYS_TRUE_CONDITION;
    }

    public boolean needsToSelectLobData() {
        return false;
    }

    public boolean canGapsOccurInCapturedDataIds() {
        return true;
    }

    public String massageDataExtractionSql(String sql, Channel channel) {
        return sql;
    }

    public String getDriverName() {
        return driverName;
    }

    public String getDriverVersion() {
        return driverVersion;
    }

    public String massageForLob(String sql, Channel channel) {
        return sql;
    }

    public boolean escapesTemplatesForDatabaseInserts() {
        return false;
    }

    public String getMasterCollation() {
        return parameterService.getString(ParameterConstants.DB_MASTER_COLLATION, "");
    }

    public boolean supportsBatchUpdates() {
        return true;
    }

    public void cleanupTriggers() {
    }

    public void addDatabaseUpgradeListener(IDatabaseUpgradeListener listener) {
        databaseUpgradeListeners.add(listener);
    }

    public AbstractTriggerTemplate getTriggerTemplate() {
        return triggerTemplate;
    }

    protected void close(ISqlTransaction transaction) {
        if (transaction != null) {
            transaction.close();
        }
    }

    public String getTablePrefix() {
        return parameterService.getTablePrefix();
    }
}