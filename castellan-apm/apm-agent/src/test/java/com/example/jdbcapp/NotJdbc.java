package com.example.jdbcapp;

/** Has a method named/shaped exactly like a JDBC target ({@code execute(String)}) but implements nothing JDBC-related — must never be woven. */
public final class NotJdbc {

    public String execute(String sql) {
        return "not woven:" + sql;
    }
}
