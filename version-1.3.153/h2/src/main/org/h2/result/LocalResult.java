/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.result;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import org.h2.engine.Database;
import org.h2.engine.Session;
import org.h2.expression.Expression;
import org.h2.message.DbException;
import org.h2.util.New;
import org.h2.util.ValueHashMap;
import org.h2.value.DataType;
import org.h2.value.Value;
import org.h2.value.ValueArray;

/**
 * A local result set contains all row data of a result set.
 * This is the object generated by engine,
 * and it is also used directly by the ResultSet class in the embedded mode.
 * If the result does not fit in memory, it is written to a temporary file.
 */
public class LocalResult implements ResultInterface, ResultTarget {

    private int maxMemoryRows;
    private Session session;
    private int visibleColumnCount;
    private Expression[] expressions;
    private int rowId, rowCount;
    private ArrayList<Value[]> rows;
    private SortOrder sort;
    private ValueHashMap<Value[]> distinctRows;
    private Value[] currentRow;
    private int offset;
    private int limit = -1;
    private ResultExternal external;
    private int diskOffset;
    private boolean distinct;
    private boolean closed;

    /**
     * Construct a local result object.
     */
    public LocalResult() {
        // nothing to do
    }

    /**
     * Construct a local result object.
     *
     * @param session the session
     * @param expressions the expression array
     * @param visibleColumnCount the number of visible columns
     */
    public LocalResult(Session session, Expression[] expressions, int visibleColumnCount) {
        this.session = session;
        if (session == null) {
            this.maxMemoryRows = Integer.MAX_VALUE;
        } else {
            this.maxMemoryRows = session.getDatabase().getMaxMemoryRows();
        }
        rows = New.arrayList();
        this.visibleColumnCount = visibleColumnCount;
        rowId = -1;
        this.expressions = expressions;
    }

    /**
     * Construct a local result set by reading all data from a regular result set.
     *
     * @param session the session
     * @param rs the result set
     * @param maxrows the maximum number of rows to read (0 for no limit)
     * @return the local result set
     */
    public static LocalResult read(Session session, ResultSet rs, int maxrows) {
        Expression[] cols = Expression.getExpressionColumns(session, rs);
        int columnCount = cols.length;
        LocalResult result = new LocalResult(session, cols, columnCount);
        try {
            for (int i = 0; (maxrows == 0 || i < maxrows) && rs.next(); i++) {
                Value[] list = new Value[columnCount];
                for (int j = 0; j < columnCount; j++) {
                    int type = result.getColumnType(j);
                    list[j] = DataType.readValue(session, rs, j + 1, type);
                }
                result.addRow(list);
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        result.done();
        return result;
    }

    /**
     * Create a shallow copy of the result set. The data and a temporary table
     * (if there is any) is not copied.
     *
     * @param targetSession the session of the copy
     * @return the copy
     */
    public LocalResult createShallowCopy(Session targetSession) {
        if (external == null && (rows == null || rows.size() < rowCount)) {
            return null;
        }
        ResultExternal e2 = null;
        if (external != null) {
            e2 = external.createShallowCopy();
            if (e2 == null) {
                return null;
            }
        }
        LocalResult copy = new LocalResult();
        copy.maxMemoryRows = this.maxMemoryRows;
        copy.session = targetSession;
        copy.visibleColumnCount = this.visibleColumnCount;
        copy.expressions = this.expressions;
        copy.rowId = -1;
        copy.rowCount = this.rowCount;
        copy.rows = this.rows;
        copy.sort = this.sort;
        copy.distinctRows = this.distinctRows;
        copy.distinct = distinct;
        copy.currentRow = null;
        copy.offset = 0;
        copy.limit = -1;
        copy.external = e2;
        copy.diskOffset = this.diskOffset;
        return copy;
    }

    /**
     * Set the sort order.
     *
     * @param sort the sort order
     */
    public void setSortOrder(SortOrder sort) {
        this.sort = sort;
    }

    /**
     * Remove duplicate rows.
     */
    public void setDistinct() {
        distinct = true;
        distinctRows = ValueHashMap.newInstance();
    }

    /**
     * Remove the row from the result set if it exists.
     *
     * @param values the row
     */
    public void removeDistinct(Value[] values) {
        if (!distinct) {
            DbException.throwInternalError();
        }
        if (distinctRows != null) {
            ValueArray array = ValueArray.get(values);
            distinctRows.remove(array);
            rowCount = distinctRows.size();
        } else {
            rowCount = external.removeRow(values);
        }
    }

    /**
     * Check if this result set contains the given row.
     *
     * @param values the row
     * @return true if the row exists
     */
    public boolean containsDistinct(Value[] values) {
        if (!distinct) {
            DbException.throwInternalError();
        }
        if (distinctRows != null) {
            ValueArray array = ValueArray.get(values);
            return distinctRows.get(array) != null;
        }
        return external.contains(values);
    }

    public void reset() {
        rowId = -1;
        if (external != null) {
            external.reset();
            if (diskOffset > 0) {
                for (int i = 0; i < diskOffset; i++) {
                    external.next();
                }
            }
        }
    }

    public Value[] currentRow() {
        return currentRow;
    }

    public boolean next() {
        if (rowId < rowCount) {
            rowId++;
            if (rowId < rowCount) {
                if (external != null) {
                    currentRow = external.next();
                } else {
                    currentRow = rows.get(rowId);
                }
                return true;
            }
            currentRow = null;
        }
        return false;
    }

    public int getRowId() {
        return rowId;
    }

    /**
     * Add a row to this object.
     *
     * @param values the row to add
     */
    public void addRow(Value[] values) {
        if (distinct) {
            if (distinctRows != null) {
                ValueArray array = ValueArray.get(values);
                distinctRows.put(array, values);
                rowCount = distinctRows.size();
                Database db = session.getDatabase();
                if (rowCount > db.getSettings().maxMemoryRowsDistinct && db.isPersistent()) {
                    external = new ResultTempTable(session, sort);
                    external.addRows(distinctRows.values());
                    distinctRows = null;
                }
            } else {
                rowCount = external.addRow(values);
            }
            return;
        }
        rows.add(values);
        rowCount++;
        if (rows.size() > maxMemoryRows && session.getDatabase().isPersistent()) {
            if (external == null) {
                external = new ResultDiskBuffer(session, sort, values.length);
            }
            addRowsToDisk();
        }
    }

    private void addRowsToDisk() {
        external.addRows(rows);
        rows.clear();
    }

    public int getVisibleColumnCount() {
        return visibleColumnCount;
    }

    /**
     * This method is called after all rows have been added.
     */
    public void done() {
        if (distinct) {
            if (distinctRows != null) {
                rows = distinctRows.values();
            } else {
                if (external != null && sort != null) {
                    // external sort
                    ResultExternal temp = external;
                    external = null;
                    temp.reset();
                    rows = New.arrayList();
                    // TODO use offset directly if possible
                    while (true) {
                        Value[] list = temp.next();
                        if (list == null) {
                            break;
                        }
                        if (external == null) {
                            external = new ResultDiskBuffer(session, sort, list.length);
                        }
                        rows.add(list);
                        if (rows.size() > maxMemoryRows) {
                            external.addRows(rows);
                            rows.clear();
                        }
                    }
                    temp.close();
                    // the remaining data in rows is written in the following lines
                }
            }
        }
        if (external != null) {
            addRowsToDisk();
            external.done();
        } else {
            if (sort != null) {
                sort.sort(rows);
            }
        }
        applyOffset();
        applyLimit();
        reset();
    }

    public int getRowCount() {
        return rowCount;
    }

    /**
     * Set the number of rows that this result will return at the maximum.
     *
     * @param limit the limit (-1 means no limit, 0 means no rows)
     */
    public void setLimit(int limit) {
        this.limit = limit;
    }

    private void applyLimit() {
        if (limit < 0) {
            return;
        }
        if (external == null) {
            if (rows.size() > limit) {
                rows = New.arrayList(rows.subList(0, limit));
                rowCount = limit;
            }
        } else {
            if (limit < rowCount) {
                rowCount = limit;
            }
        }
    }

    public boolean needToClose() {
        return external != null;
    }

    public void close() {
        if (external != null) {
            external.close();
            external = null;
            closed = true;
        }
    }

    public String getAlias(int i) {
        return expressions[i].getAlias();
    }

    public String getTableName(int i) {
        return expressions[i].getTableName();
    }

    public String getSchemaName(int i) {
        return expressions[i].getSchemaName();
    }

    public int getDisplaySize(int i) {
        return expressions[i].getDisplaySize();
    }

    public String getColumnName(int i) {
        return expressions[i].getColumnName();
    }

    public int getColumnType(int i) {
        return expressions[i].getType();
    }

    public long getColumnPrecision(int i) {
        return expressions[i].getPrecision();
    }

    public int getNullable(int i) {
        return expressions[i].getNullable();
    }

    public boolean isAutoIncrement(int i) {
        return expressions[i].isAutoIncrement();
    }

    public int getColumnScale(int i) {
        return expressions[i].getScale();
    }

    /**
     * Set the offset of the first row to return.
     *
     * @param offset the offset
     */
    public void setOffset(int offset) {
        this.offset = offset;
    }

    private void applyOffset() {
        if (offset <= 0) {
            return;
        }
        if (external == null) {
            if (offset >= rows.size()) {
                rows.clear();
                rowCount = 0;
            } else {
                // avoid copying the whole array for each row
                int remove = Math.min(offset, rows.size());
                rows = New.arrayList(rows.subList(remove, rows.size()));
                rowCount -= remove;
            }
        } else {
            if (offset >= rowCount) {
                rowCount = 0;
            } else {
                diskOffset = offset;
                rowCount -= offset;
            }
        }
    }

    public String toString() {
        return "columns: " + visibleColumnCount + " rows: " + rowCount + " pos: " + rowId;
    }

    /**
     * Check if this result set is closed.
     *
     * @return true if it is
     */
    public boolean isClosed() {
        return closed;
    }

    public int getFetchSize() {
        return 0;
    }

    public void setFetchSize(int fetchSize) {
        // ignore
    }

}
