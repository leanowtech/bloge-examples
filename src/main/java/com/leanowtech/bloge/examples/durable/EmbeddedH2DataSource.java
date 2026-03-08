package com.leanowtech.bloge.examples.durable;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

final class EmbeddedH2DataSource implements DataSource {

    private final String url;
    private final String user;
    private final String password;

    private EmbeddedH2DataSource(String dbName) {
        this.url = "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1";
        this.user = "sa";
        this.password = "";
    }

    static DataSource inMemory(String dbName) {
        return new EmbeddedH2DataSource(dbName);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String pwd) throws SQLException {
        return DriverManager.getConnection(url, username, pwd);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("unwrap is not supported");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }

    @Override
    public PrintWriter getLogWriter() {
        return DriverManager.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        DriverManager.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) {
        DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
        return DriverManager.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("Parent logger is not supported");
    }
}
