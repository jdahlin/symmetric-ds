package org.jumpmind.db.platform.mssql;

import javax.sql.DataSource;

import org.jumpmind.db.sql.SqlTemplateSettings;
import org.jumpmind.db.sql.ISqlTransaction;
import org.jumpmind.db.sql.JdbcSqlTemplate;

public class MsSqlJdbcSqlTemplate extends JdbcSqlTemplate {

    public MsSqlJdbcSqlTemplate(DataSource dataSource, SqlTemplateSettings settings) {
        super(dataSource, settings, null);
        primaryKeyViolationCodes = new int[] {2627};
    }
    
    @Override
    public ISqlTransaction startSqlTransaction() {
        return new MsSqlJdbcSqlTransaction(this);
    }
   
    @Override
    protected boolean allowsNullForIdentityColumn() {
        return false;
    }
    
}