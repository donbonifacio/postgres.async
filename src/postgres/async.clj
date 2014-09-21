(ns postgres.async
  (:require [postgres.async.impl :refer [consumer-fn defasync] :as pg])
  (:import [com.github.pgasync
            QueryExecutor TransactionExecutor Transaction
            ConnectionPool ConnectionPoolBuilder]
           [com.github.pgasync.impl.conversion DataConverter]))

(set! *warn-on-reflection* true)

(defmulti from-pg-value (fn [oid value] oid))
(defprotocol IPgParameter
  (to-pg-value [value]))

(defn- create-converter []
  (proxy [DataConverter] []
    (toConvertable [oid value]
      (from-pg-value oid value))
    (fromConvertable [value]
      (to-pg-value value))))

(defn open-db [{:keys [hostname port username password database pool-size]}]
  "Creates a db connection pool"
  (-> (ConnectionPoolBuilder.)
      (.hostname hostname)
      (.port (or port 5432))
      (.database database)
      (.username username)
      (.password password)
      (.poolSize (or pool-size 25))
      (.dataConverter (create-converter))
      (.build)))

(defn close-db! [^ConnectionPool db]
  "Closes a db connection pool"
  (.close db))

(defn execute! [^QueryExecutor db [sql & params] f]
  "Executes an sql statement and calls (f result-set exception) on completion"
  (.query db sql params
          (consumer-fn [rs]
                       (f (pg/result->map rs) nil))
          (consumer-fn [exception]
                       (f nil exception))))

(defn query! [db sql f]
  "Executes an sql query and calls (f rows exception) on completion"
  (execute! db sql (fn [rs err]
                     (f (:rows rs) err))))

(defn insert! [db sql-spec data f]
  "Executes an sql insert and calls (f result-set exception) on completion.
   Spec format is
     :table - table name
     :returning - sql string"
  (execute! db (list* (pg/create-insert-sql sql-spec data)
                    (for [e data] (second e)))
          f))

(defn update! [db sql-spec data f]
  "Executes an sql update and calls (f result-set exception) on completion.
   Spec format is
     :table - table name
     :returning - sql string
     :where - [sql & params]"
  (execute! db (flatten [(pg/create-update-sql sql-spec data)
                        (rest (:where sql-spec))
                        (for [e data] (second e))])
          f))

(defn begin! [^TransactionExecutor db f]
  "Begins a transaction and calls (f transaction exception) on completion"
  (.begin db
          (consumer-fn [tx]
                       (f tx nil))
          (consumer-fn [exception]
                       (f nil exception))))

(defn commit! [^Transaction tx f]
  "Commits an active transaction and calls (f true exception) on completion"
  (.commit tx
           #(f true nil)
           (consumer-fn [exception]
                        (f nil exception))))

(defn rollback! [^Transaction tx f]
  "Rollbacks an active transaction and calls (f true exception) on completion"
  (.rollback tx
             #(f true nil)
             (consumer-fn [exception]
                          (f nil exception))))

(defasync <execute!  [db query])
(defasync <query!    [db query])
(defasync <insert!   [db sql-spec data])
(defasync <update!   [db sql-spec data])
(defasync <begin!    [db])
(defasync <commit!   [tx])
(defasync <rollback! [tx])

(defmacro dosql [bindings & forms]
  "Takes values from channels returned by db functions and returns [nil exception]
   on first error. Returns [result-of-body nil] on success."
  (let [err (gensym "e")]
    `(let [~@(pg/async-sql-bindings bindings err)]
       (if ~err
         [nil ~err]
         [(do ~@forms) nil]))))