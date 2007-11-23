package sqlite;

import sqlite.internal.*;
import static sqlite.internal.SQLiteConstants.*;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLiteConnection is a single connection to sqlite database. Most methods are thread-confined,
 * and will throw errors if called from alien thread. Confinement thread is defined at the
 * construction time.
 * <p/>
 * SQLiteConnection should be expicitly closed before the object is disposed. Failing to do so
 * may result in unpredictable behavior from sqlite.
 */
public final class SQLiteConnection {
  /**
   * The database file, or null if it is memory database
   */
  private final File myFile;
  private final int myNumber = Internal.nextConnectionNumber();
  private final Object myLock = new Object();

  /**
   * Confinement thread, set on open().
   */
  private volatile Thread myConfinement;

  /**
   * Handle to the db. Almost confined: usually not changed outside the confining thread, except for close() method.
   */
  private SWIGTYPE_p_sqlite3 myHandle;

  /**
   * When connection is closed, it cannot be used anymore.
   */
  private boolean myDisposed;

  /**
   * Prepared statements. Almost confined.
   */
  private final Map<String, SWIGTYPE_p_sqlite3_stmt> myStatementCache = new HashMap<String, SWIGTYPE_p_sqlite3_stmt>();
  private final Map<SWIGTYPE_p_sqlite3_stmt, String> myStatements = new LinkedHashMap<SWIGTYPE_p_sqlite3_stmt, String>();

  private final StatementController myCachedController = new CachedStatementController();
  private final StatementController myUncachedController = new UncachedStatementController();

  /**
   * Create connection to database located in the specified file.
   * Database is not opened by this method, but the whole object is being confined to
   * the calling thread. So call the constructor only in the thread which will be used
   * to work with the connection.
   *
   * @param dbfile database file, or null for memory database
   */
  public SQLiteConnection(File dbfile) {
    myFile = dbfile;
    Internal.logger.info(this + " created(" + myFile + ")");
  }

  /**
   * Create connection to in-memory temporary database.
   *
   * @see #SQLiteConnection(java.io.File)
   */
  public SQLiteConnection() {
    this(null);
  }

  /**
   * @return the file hosting the database, or null if database is in memory
   */
  public File getDatabaseFile() {
    return myFile;
  }

  public boolean isMemoryDatabase() {
    return myFile == null;
  }

  /**
   * Opens database, creating it if needed.
   *
   * @see #open(boolean)
   */
  public SQLiteConnection open() throws SQLiteException {
    return open(true);
  }

  /**
   * Opens database. If database is already open, fails gracefully, allowing process
   * to continue in production mode.
   *
   * @param allowCreate if true, database file may be created. For in-memory database, must
   *                    be true
   */
  public SQLiteConnection open(boolean allowCreate) throws SQLiteException {
    int flags = Open.SQLITE_OPEN_READWRITE;
    if (!allowCreate) {
      if (isMemoryDatabase()) {
        throw new SQLiteException(Wrapper.WRAPPER_WEIRD, "cannot open memory database without creation");
      }
    } else {
      flags |= Open.SQLITE_OPEN_CREATE;
    }
    openX(flags);
    return this;
  }

  /**
   * Opens database is read-only mode. Not applicable for in-memory database.
   */
  public SQLiteConnection openReadonly() throws SQLiteException {
    if (isMemoryDatabase()) {
      throw new SQLiteException(Wrapper.WRAPPER_WEIRD, "cannot open memory database in read-only mode");
    }
    openX(Open.SQLITE_OPEN_READONLY);
    return this;
  }

  /**
   * Tells whether database is open. May be called from another thread.
   */
  public boolean isOpen() {
    synchronized (myLock) {
      return myHandle != null && !myDisposed;
    }
  }

  public boolean isDisposed() {
    synchronized (myLock) {
      return myDisposed;
    }
  }

  /**
   * Closes connection.
   * <p/>
   * This method may be called from another thread.
   */
  public void dispose() {
    SWIGTYPE_p_sqlite3 handle;
    synchronized (myLock) {
      if (myDisposed)
        return;
      myDisposed = true;
      handle = myHandle;
      myHandle = null;
      myConfinement = null;
    }
    if (handle == null)
      return;
    finalizeStatements();
    int rc = _SQLiteSwigged.sqlite3_close(handle);
    // rc may be SQLiteConstants.Result.SQLITE_BUSY if statements are open
    if (rc != SQLiteConstants.Result.SQLITE_OK) {
      String errmsg = null;
      try {
        errmsg = _SQLiteSwigged.sqlite3_errmsg(handle);
      } catch (Exception e) {
        Internal.logger.log(Level.WARNING, "cannot get sqlite3_errmsg", e);
      }
      Internal.logger.warning(this + " close error " + rc + (errmsg == null ? "" : ": " + errmsg));
    }
    Internal.logger.info(this + " closed");
  }

  public SQLiteConnection exec(String sql) throws SQLiteException {
    checkThread();
    String[] error = {null};
    int rc = _SQLiteManual.sqlite3_exec(handle(), sql, error);
    throwResult(rc, "exec()", error[0]);
    return this;
  }

  public SQLiteStatement prepare(String sql) throws SQLiteException {
    return prepare(sql, true);
  }

  public SQLiteStatement prepare(String sql, boolean cached) throws SQLiteException {
    checkThread();
    SWIGTYPE_p_sqlite3 handle;
    SWIGTYPE_p_sqlite3_stmt stmt = null;
    int openCounter;
    synchronized (myLock) {
      if (cached) {
        // while the statement is in work, it is removed from cache
        // see cachedStatementDisposed()
        stmt = myStatementCache.remove(sql);
      }
      handle = handle();
    }
    if (stmt == null) {
      int[] rc = {Integer.MIN_VALUE};
      stmt = _SQLiteManual.sqlite3_prepare_v2(handle, sql, rc);
      throwResult(rc[0], "prepare()", sql);
      if (stmt == null)
        throw new SQLiteException(Wrapper.WRAPPER_WEIRD, "sqlite did not return stmt");
    }
    SQLiteStatement statement = null;
    synchronized (myLock) {
      // the connection may close while prepare in progress
      // most probably that would throw SQLiteException earlier, but we'll check anyway
      if (myHandle != null) {
        myStatements.put(stmt, sql);
        StatementController controller = cached ? myCachedController : myUncachedController;
        statement = new SQLiteStatement(controller, stmt, sql);
      }
    }
    if (statement == null) {
      // connection closed
      try {
        throwResult(_SQLiteSwigged.sqlite3_finalize(stmt), "finalize() in prepare()");
      } catch (Exception e) {
        // ignore
      }
      throw new SQLiteException(Wrapper.WRAPPER_NOT_OPENED, "connection closed while prepare() was in progress");
    }
    return statement;
  }

  private void finalizeStatements() {
    boolean alienThread = myConfinement != Thread.currentThread();
    if (!alienThread) {
      while (true) {
        SWIGTYPE_p_sqlite3_stmt stmt = null;
        String sql = null;
        synchronized (myLock) {
          if (!myStatements.isEmpty()) {
            Map.Entry<SWIGTYPE_p_sqlite3_stmt, String> e = myStatements.entrySet().iterator().next();
            stmt = e.getKey();
            sql = e.getValue();
          } else {
            break;
          }
        }
        finalizeStatement(stmt, sql);
      }
    }
    synchronized (myLock) {
      if (!myStatements.isEmpty()) {
        int count = myStatements.size();
        if (alienThread) {
          Internal.logger.warning("cannot finalize " + count + " statements from alien thread");
        } else {
          Internal.recoverableError(this, count + " statements are not finalized", false);
        }
      }
      myStatements.clear();
      myStatementCache.clear();
    }
  }

  private void finalizeStatement(SWIGTYPE_p_sqlite3_stmt stmt, String sql) {
    int rc = _SQLiteSwigged.sqlite3_finalize(stmt);
    if (rc != Result.SQLITE_OK) {
      Internal.logger.warning("error [" + rc + "] finishing statement [" + sql + "]");
    }
    synchronized (myLock) {
      String removedSql = myStatements.remove(stmt);
      if (removedSql == null) {
        Internal.recoverableError(stmt, "alien statement for " + sql, true);
      } else if (removedSql != sql) {
        Internal.recoverableError(stmt, "different sql [" + sql + "][" + removedSql + "]", true);
      }
      SWIGTYPE_p_sqlite3_stmt cached = myStatementCache.remove(sql);
      if (cached != null && cached != stmt) {
        // cache has another statement of the same sql, put it back
        myStatementCache.put(sql, cached);
      }
    }
  }

  private void pushCache(SWIGTYPE_p_sqlite3_stmt stmt, String sql, boolean hasBindings, boolean hasStepped) {
    boolean finalize = false;
    try {
      if (hasStepped) {
        int rc = _SQLiteSwigged.sqlite3_reset(stmt);
        throwResult(rc, "reset");
      }
      if (hasBindings) {
        int rc = _SQLiteSwigged.sqlite3_clear_bindings(stmt);
        throwResult(rc, "clearBindings");
      }
      synchronized (myLock) {
        SWIGTYPE_p_sqlite3_stmt expunged = myStatementCache.put(sql, stmt);
        if (expunged != null) {
          if (expunged == stmt) {
            Internal.recoverableError(stmt, "appeared in cache when inserted", true);
          } else {
            // put it back
            // todo log
            myStatementCache.put(sql, expunged);
            finalize = true;
          }
        }
      }
    } catch (SQLiteException e) {
      Internal.logger.log(Level.WARNING, "exception clearing statement", e);
      finalize = true;
    }
    if (finalize) {
      finalizeStatement(stmt, sql);
    }
  }

  private SWIGTYPE_p_sqlite3 handle() throws SQLiteException {
    synchronized (myLock) {
      if (myDisposed)
        throw new SQLiteException(Wrapper.WRAPPER_MISUSE, "connection is disposed");
      SWIGTYPE_p_sqlite3 handle = myHandle;
      if (handle == null)
        throw new SQLiteException(Wrapper.WRAPPER_NOT_OPENED, null);
      return handle;
    }
  }

  void throwResult(int resultCode, String operation) throws SQLiteException {
    throwResult(resultCode, operation, null);
  }

  void throwResult(int resultCode, String operation, Object additional) throws SQLiteException {
    if (resultCode != SQLiteConstants.Result.SQLITE_OK) {
      // ignore sync
      SWIGTYPE_p_sqlite3 handle = myHandle;
      String message = this + " " + operation;
      String additionalMessage = additional == null ? null : String.valueOf(additional);
      if (additionalMessage != null)
        message += " " + additionalMessage;
      if (handle != null) {
        try {
          String errmsg = _SQLiteSwigged.sqlite3_errmsg(handle);
          if (additionalMessage == null || !additionalMessage.equals(errmsg)) {
            message += " [" + errmsg + "]";
          }
        } catch (Exception e) {
          Internal.logger.log(Level.WARNING, "cannot get sqlite3_errmsg", e);
        }
      }
      throw new SQLiteException(resultCode, message);
    }
  }

  private void openX(int flags) throws SQLiteException {
    SQLite.loadLibrary();
    SWIGTYPE_p_sqlite3 handle;
    synchronized (myLock) {
      if (myDisposed) {
        throw new SQLiteException(Wrapper.WRAPPER_MISUSE, "cannot reopen closed connection");
      }
      if (myConfinement == null) {
        myConfinement = Thread.currentThread();
        Internal.logger.fine(this + " confined to " + myConfinement);
      } else {
        checkThread();
      }
      handle = myHandle;
    }
    if (handle != null) {
      Internal.recoverableError(this, "already opened", true);
      return;
    }
    String dbname = getSqliteDbName();
    int[] rc = {Integer.MIN_VALUE};
    handle = _SQLiteManual.sqlite3_open_v2(dbname, flags, rc);
    if (rc[0] != Result.SQLITE_OK) {
      if (handle != null) {
        try {
          _SQLiteSwigged.sqlite3_close(handle);
        } catch (Exception e) {
          // ignore
        }
      }
      String errorMessage = _SQLiteSwigged.sqlite3_errmsg(null);
      throw new SQLiteException(rc[0], errorMessage);
    }
    if (handle == null) {
      throw new SQLiteException(Wrapper.WRAPPER_WEIRD, "sqlite didn't return db handle");
    }
    synchronized (myLock) {
      myHandle = handle;
    }
    Internal.logger.info(this + " opened(" + flags + ")");
  }

  private String getSqliteDbName() {
    return myFile == null ? ":memory:" : myFile.getAbsolutePath();
  }

  int getStatementCount() {
    synchronized (myLock) {
      return myStatements.size();
    }
  }

  void checkThread() throws SQLiteException {
    Thread confinement = myConfinement;
    if (confinement == null)
      return;
    Thread thread = Thread.currentThread();
    if (thread != confinement) {
      String message = this + " confined(" + confinement + ") used(" + thread + ")";
      throw new SQLiteException(Wrapper.WRAPPER_CONFINEMENT_VIOLATED, message);
    }
  }

  public String toString() {
    return "sqlite[" + myNumber + "]";
  }

  protected void finalize() throws Throwable {
    super.finalize();
    SWIGTYPE_p_sqlite3 handle = myHandle;
    boolean disposed = myDisposed;
    if (handle != null || !disposed) {
      Internal.recoverableError(this, "wasn't disposed before finalizing", true);
      try {
        dispose();
      } catch (Throwable e) {
        // ignore
      }
    }
  }

  private abstract class BaseStatementController implements StatementController {
    public void validate() throws SQLiteException {
      SQLiteConnection.this.checkThread();
      SQLiteConnection.this.handle();
    }

    public void throwResult(int rc, String message, Object additionalMessage) throws SQLiteException {
      SQLiteConnection.this.throwResult(rc, message, additionalMessage);
    }

    protected boolean checkDispose(String sql) {
      try {
        SQLiteConnection.this.checkThread();
      } catch (SQLiteException e) {
        Internal.recoverableError(this, "disposing [" + sql + "] from alien thread", true);
        return false;
      }
      return true;
    }
  }

  private class CachedStatementController extends BaseStatementController {
    public void disposed(SWIGTYPE_p_sqlite3_stmt handle, String sql, boolean hasBindings, boolean hasStepped) {
      if (checkDispose(sql)) {
        SQLiteConnection.this.pushCache(handle, sql, hasBindings, hasStepped);
      }
    }

    public String toString() {
      return SQLiteConnection.this.toString() + "[C]";
    }
  }

  private class UncachedStatementController extends BaseStatementController {
    public void disposed(SWIGTYPE_p_sqlite3_stmt handle, String sql, boolean hasBindings, boolean hasStepped) {
      if (checkDispose(sql)) {
        SQLiteConnection.this.finalizeStatement(handle, sql);
      }
    }

    public String toString() {
      return SQLiteConnection.this.toString() + "[U]";
    }
  }
}