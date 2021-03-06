(ns clojure-hbase.core
  (:refer-clojure :rename {get map-get})
  (:use clojure-hbase.internal)
  (:import [org.apache.hadoop.hbase HBaseConfiguration HConstants KeyValue]
           [org.apache.hadoop.hbase.client HTablePool Get Put Delete Scan Result HTableInterface]
           [org.apache.hadoop.hbase.util Bytes]))

(def ^{:private true} put-class
  (Class/forName "org.apache.hadoop.hbase.client.Put"))
(def ^{:private true} get-class
  (Class/forName "org.apache.hadoop.hbase.client.Get"))
(def ^{:private true} delete-class
  (Class/forName "org.apache.hadoop.hbase.client.Delete"))
(def ^{:private true} scan-class
  (Class/forName "org.apache.hadoop.hbase.client.Scan"))

;; This holds the HTablePool reference for all users. Users never have to see
;; this, so we just hide this detail from the user.
(def ^{:dynamic true :private true} *db*
  (atom nil))

;; There doesn't appear to be, as far as I can tell, a way to get the current
;; HBaseConfiguration being used by an HTablePool. Unfortunately, this means
;; you need to remember and keep track of this yourself, if you want to be
;; switching them around.
(defn default-config
  "Returns the default HBaseConfiguration as a map."
  []
  (into {} (HBaseConfiguration/create)))

(defn make-config
  "Constructs a default HBaseConfiguration object and sets the options given in
   config-map.

   Example: (make-config
              {\"hbase.zookeeper.dns.interface\" \"lo\"
              :hbase.zookeeper.quorum \"127.0.0.1\"})"
  [config-map]
  (let [config-obj (HBaseConfiguration/create)]
    (doseq [[option value] (seq config-map)]
      (.set config-obj (name option) (name value)))
    config-obj))

(defn set-config
  "Resets the *db* atom, so that subsequent calls to htable-pool
   use the new configuration (a HBaseConfiguration object).

   Example: (set-config
              (make-config
                {\"hbase.zookeeper.dns.interface\" \"lo\"
                :hbase.zookeeper.quorum \"127.0.0.1\"})"
  [^HBaseConfiguration config-obj]
  (reset! *db* (HTablePool. config-obj Integer/MAX_VALUE)))

(defn- ^HTablePool htable-pool
  []
  (if-let [pool @*db*]
    pool
    (swap! *db* (fn [curr-db] (or curr-db (HTablePool.))))))

(defmulti to-bytes-impl
  "Converts its argument into an array of bytes. By default, uses HBase's
   Bytes/toBytes and does nothing to byte arrays. Since it is a multimethod
   you can redefine it to create your own serialization routines for new types.

   This multimethod is mainly for internal use, and may be made private in
   a future release. You should probably be handling the serialization and
   deserialization yourself at the user level. That is, prefer giving the
   API functions byte arrays or Strings that you have written to and will
   read from. Note: This multimethod does not serialize everything so that
   clojure's `read` function will read them back."
  {:deprecated "0.92.2"}
  class)
(defmethod to-bytes-impl (Class/forName "[B")
  [arg]
  arg)
(defmethod to-bytes-impl clojure.lang.Keyword
  [arg]
  (Bytes/toBytes ^String (name arg)))
(defmethod to-bytes-impl clojure.lang.Symbol
  [arg]
  (Bytes/toBytes ^String (name arg)))
(defmethod to-bytes-impl clojure.lang.IPersistentList
  [arg]
  ;; *print-dup* false prevents metadata from being printed, and
  ;; thus requiring an EvalReader.
  (let [list-as-str (binding [*print-dup* false] (pr-str arg))]
    (Bytes/toBytes ^String list-as-str)))
(defmethod to-bytes-impl clojure.lang.IPersistentVector
  [arg]
  ;; *print-dup* false prevents metadata from being printed
  (let [vec-as-str (binding [*print-dup* false] (pr-str arg))]
    (Bytes/toBytes ^String vec-as-str)))
(defmethod to-bytes-impl clojure.lang.IPersistentMap
  [arg]
  ;; *print-dup* false prevents metadata from being printed
  (let [map-as-str (binding [*print-dup* false] (pr-str arg))]
    (Bytes/toBytes ^String map-as-str)))
(defmethod to-bytes-impl clojure.lang.IPersistentSet
  [arg]
  ;; *print-dup* false prevents metadata from being printed
  (let [set-as-str (binding [*print-dup* false] (pr-str arg))]
    (Bytes/toBytes ^String set-as-str)))
(defmethod to-bytes-impl :default
  [arg]
  (Bytes/toBytes arg))

(defn to-bytes
  "Converts its argument to an array of bytes using the to-bytes-impl
   multimethod. We can't type hint a multimethod, so we type hint this
   shell function and calls all over this module don't need reflection.

   Deprecated: This method is now intended mainly for internal use and
   may be made private in a future release. You should be handling the
   serialization and deserialization yourself at the user level. That is, prefer
   giving the API functions byte arrays or Strings that you have written
   to and will read from."
  {:tag (Class/forName "[B")
   :deprecated "0.92.2"}
  [arg]
  (to-bytes-impl arg))

(defn as-map
  "Extracts the contents of the Result object and sticks them into a 3-level
   map, indexed by family, qualifier, and then timestamp.

   Functions can be passed in with arguments :map-family, :map-qualifier,
   :map-timestamp, and :map-value. You can also use :map-default to pick a
   default function, which will be overriden by the more specific directives."
  [#^Result result & args]
  (let [options      (into {} (map vec (partition 2 args)))
        default-fn   (map-get options :map-default identity)
        family-fn    (map-get options :map-family default-fn)
        qualifier-fn (map-get options :map-qualifier default-fn)
        timestamp-fn (map-get options :map-timestamp default-fn)
        value-fn     (map-get options :map-value default-fn)]
    (loop [remaining-kvs (seq (.raw result))
           kv-map {}]
      (if-let [^KeyValue kv (first remaining-kvs)]
        (let [family    (family-fn (.getFamily kv))
              qualifier (qualifier-fn (.getQualifier kv))
              timestamp (timestamp-fn (.getTimestamp kv))
              value     (value-fn (.getValue kv))]
          (recur (next remaining-kvs)
                 (assoc-in kv-map [family qualifier timestamp] value)))
        kv-map))))

(defn latest-as-map
  "Extracts the contents of the Result object and sticks them into a 2-level
   map, indexed by family and qualifier. The latest timestamp is used.

   Functions can be passed in with arguments :map-family, :map-qualifier,
   and :map-value. You can also use :map-default to pick a default function,
   which will be overriden by the more specific directives."
  [#^Result result & args]
  (let [options      (into {} (map vec (partition 2 args)))
        default-fn   (map-get options :map-default identity)
        family-fn    (map-get options :map-family default-fn)
        qualifier-fn (map-get options :map-qualifier default-fn)
        value-fn     (map-get options :map-value default-fn)]
    (loop [remaining-kvs (seq (.raw result))
           keys #{}]
      (if-let [^KeyValue kv (first remaining-kvs)]
        (let [family    (.getFamily kv)
              qualifier (.getQualifier kv)]
          (recur (next remaining-kvs)
                 (conj keys [family qualifier])))
        ;; At this point, we have a duplicate-less list of [f q] keys in keys.
        ;; Go back through, pulling the latest values for these keys.
        (loop [remaining-keys keys
               kv-map {}]
          (if-let [[family qualifier] (first remaining-keys)]
            (recur (next remaining-keys)
                   (assoc-in kv-map [(family-fn family)
                                     (qualifier-fn qualifier)]
                             (value-fn (.getValue result family qualifier))))
            kv-map))))))

(defn as-vector
  "Extracts the contents of the Result object and sticks them into a
   vector tuple of [family qualifier timestamp value]; returns a sequence
   of such vectors.

   Functions can be passed in with arguments :map-family, :map-qualifier,
   :map-timestamp, and :map-value. You can also use :map-default to pick a
   default function, which will be overriden by the more specific directives."
  [#^Result result & args]
  (let [options      (into {} (map vec (partition 2 args)))
        default-fn   (map-get options :map-default identity)
        family-fn    (map-get options :map-family default-fn)
        qualifier-fn (map-get options :map-qualifier default-fn)
        timestamp-fn (map-get options :map-timestamp default-fn)
        value-fn     (map-get options :map-value default-fn)]
    (loop [remaining-kvs (seq (.raw result))
           kv-vec (transient [])]
      (if-let [^KeyValue kv (first remaining-kvs)]
        (let [family    (family-fn (.getFamily kv))
              qualifier (qualifier-fn (.getQualifier kv))
              timestamp (timestamp-fn (.getTimestamp kv))
              value     (value-fn (.getValue kv))]
          (recur (next remaining-kvs)
                 (conj! kv-vec [family qualifier timestamp value])))
        (persistent! kv-vec)))))

(defn scanner
  "Creates a Scanner on the given table using the given Scan."
  [#^HTableInterface table #^Scan scan]
  (io!
   (.getScanner table scan)))

(defn table
  "Gets an HTable from the open HTablePool by name."
  [table-name]
  (io!
   (.getTable (htable-pool) (to-bytes table-name))))

(defn release-table
  "Puts an HTable back into the open HTablePool."
  [#^HTableInterface table]
  (io!
   (.putTable (htable-pool) table)))

;; with-table and with-scanner are basically the same function, but I couldn't
;; figure out a way to generate them both with the same macro.
(defmacro with-table
  "Executes body, after creating the tables given in bindings. Any table
   created in this way (use the function table) will automatically be returned
   to the HTablePool when the body finishes."
  [bindings & body]
  {:pre [(vector? bindings)
         (even? (count bindings))]}
  (cond
   (= (count bindings) 0) `(do ~@body)
   (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                             (try
                               (with-table ~(subvec bindings 2) ~@body)
                               (finally
                                (release-table ~(bindings 0)))))
   :else (throw (IllegalArgumentException.
                 "Bindings must be symbols."))))

(defmacro with-scanner
  "Executes body, after creating the scanners given in the bindings. Any scanner
   created in this way (use the function scanner or scan!) will automatically
   be closed when the body finishes."
  [bindings & body]
  {:pre [(vector? bindings)
         (even? (count bindings))]}
  (cond
   (= (count bindings) 0) `(do ~@body)
   (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                             (try
                               (with-scanner ~(subvec bindings 2) ~@body)
                               (finally
                                (.close ~(bindings 0)))))
   :else (throw (IllegalArgumentException. "Bindings must be symbols."))))

(defprotocol HBaseOperation
  "This protocol defines an `execute` operation that can be defined on
   an object that represents some request against a table. Implement it
   for objects that you wish to use with the `execute` function and which
   can be expressed in this way."
  (execute-operation [operation table]
    "Run the given operation object on the HTable given."))

(extend-protocol HBaseOperation
  Get
  (execute-operation [operation table] (.get ^HTableInterface table
                                             ^Get operation))
  Put
  (execute-operation [operation table] (.put ^HTableInterface table
                                             ^Put operation))
  Scan
  (execute-operation [operation table] (scanner table operation))
  Delete
  (execute-operation [operation table] (.delete ^HTableInterface table
                                                ^Delete operation)))

(defn execute
  "Performs the given HBase table operations (anything implementing the
   HBaseOperation protocol, such as Get/Scan/Put/Delete) on the given
   HTableInterface. The return value will be a sequence of equal length,
   with each slot containing the results of the query in the corresponding
   position."
  [^HTableInterface table & ops]
  (io! (loop [results (transient [])
              remaining-ops ops]
         (if (empty? remaining-ops)
           (persistent! results)
           (recur (conj! results (execute-operation (first remaining-ops)
                                                    table))
                  (rest remaining-ops))))))

(defn query
  "Performs the given query actions (Get/Scan) on the given HTable. The return
   value will be a sequence of equal length, with each slot containing the
   results of the query in the corresponding position.

   DEPRECATED: Use 'execute' instead."
  {:deprecated "0.92.2"}
  [table & ops]
  (apply execute table ops))

(defn modify
  "Performs the given modifying actions (Put/Delete) on the given HTable.

   DEPRECATED: Use 'execute' instead."
  {:deprecated "0.92.2"}
  [table & ops]
  (apply execute table ops))

;;
;;  GET
;;

(defn- make-get
  "Makes a Get object, taking into account user construction
   directives, such as using an existing Get, or passing a
   pre-existing RowLock. Returns a two-element vector; the first
   element contains the new Get object, the second contains the
   specs with any construction directives removed."
  [row options]
  (let [row (to-bytes row)
        directives #{:use-existing}
        cons-opts (apply hash-map (flatten (filter
                                            #(contains? directives
                                                        (first %)) options)))
        get-obj (cond (contains? cons-opts :use-existing)
                      (io! (:use-existing cons-opts))
                      :else
                      (new Get row))]
    [get-obj (filter #(not (contains? directives (first %))) options)]))

;; This maps each get command to its number of arguments, for helping us
;; partition the command sequence.
(def ^{:private true} get-argnums
  {:column       1    ;; :column [:family-name :qualifier]
   :columns      1    ;; :columns [:family-name [:qual1 :qual2...]...]
   :family       1    ;; :family :family-name
   :families     1    ;; :families [:family1 :family2 ...]
   :filter       1    ;; :filter <a filter you've made>
   :all-versions 0    ;; :all-versions
   :max-versions 1    ;; :max-versions <int>
   :time-range   1    ;; :time-range [start end]
   :time-stamp   1    ;; :time-stamp time
   :use-existing 1})  ;; :use-existing <some Get you've made>

(defn- apply-columns
  "The first argument should be a function of two arguments: a column family
   name and a column name. The second argument should be the sequence specified
   by a :columns [:family [:col1 :col2 :col3...] ...] spec (the outer vector in
   the preceding example). apply-columns will perform the first argument on
   every (column-family,column) pair specified in the second argument."
  [func columns]
  (doseq [column (partition 2 columns)] ;; :family [:cols...] pairs.
    (let [[family qualifiers] column]
      (doseq [q qualifiers]
        (func family q)))))

(defn get*
  "Returns a Get object suitable for performing a get on an HTable. To make
   modifications to an existing Get object, pass it as the argument to
   :use-existing; to use an existing RowLock, pass it as the argument to
   :row-lock."
  [row & args]
  (let [specs (partition-query args get-argnums)
        [#^Get get-op specs] (make-get row specs)]
    (doseq [spec specs]
      (condp = (first spec)
          :column       (apply #(.addColumn get-op
                                            (to-bytes %1) (to-bytes %2))
                               (second spec))
          :columns      (apply-columns #(.addColumn get-op
                                                    (to-bytes %1) (to-bytes %2))
                                       (second spec))
          :family       (.addFamily get-op (to-bytes (second spec)))
          :families     (doseq [f (second spec)]
                          (.addFamily get-op (to-bytes f)))
          :filter       (.setFilter get-op (second spec))
          :all-versions (.setMaxVersions get-op)
          :max-versions (.setMaxVersions get-op (second spec))
          :time-range   (apply #(.setTimeRange get-op %1 %2) (second spec))
          :time-stamp   (.setTimeStamp get-op (second spec))))
    get-op))


(defn get
  "Creates and executes a Get object against the given table. Options are
   the same as for get."
  [#^HTableInterface table row & args]
  (let [g #^Get (apply get* row args)]
    (io!
     (.get table g))))

;;
;;  PUT
;;

;; This maps each put command to its number of arguments, for helping us
;; partition the command sequence.
(def ^{:private true} put-argnums
  {:value        1    ;; :value [:family :column <value>]
   :values       1    ;; :values [:family [:column1 value1 ...] ...]
   :with-timestamp 2  ;; :with-timestamp ts [:value [:family ...] ...],
                      ;;  any number of :value or :values nested
   :write-to-WAL 1    ;; :write-to-WAL true/false
   :use-existing 1})  ;; :use-existing <a Put you've made>

(defn- make-put
  "Makes a Put object, taking into account user construction directives, such as
   using an existing Put, or passing a pre-existing RowLock. Returns a two-item
   vector, with the Put object returned in the first element, and the remaining,
   non-construction options in the second."
  [row options]
  (let [row (to-bytes row)
        directives #{:use-existing}
        cons-opts (apply hash-map (flatten (filter
                                            #(contains? directives
                                                        (first %)) options)))
        put-obj (cond (contains? cons-opts :use-existing)
                      (io! (:use-existing cons-opts))
                      :else
                      (new Put row))]
    [put-obj (filter #(not (contains? directives (first %))) options)]))

;; Separate functions for -ts variants, despite code duplication, are
;; to call the functions in a way that lets the server determine the
;; timestamp when there is none specified. Seems the less surprising
;; behavior.

(defn- put-add
  [^Put put-op family qualifier value]
  (.add put-op (to-bytes family) (to-bytes qualifier) (to-bytes value)))

(defn- put-add-ts
  [^Put put-op ts family qualifier value]
  (.add put-op (to-bytes family) (to-bytes qualifier) ts (to-bytes value)))

(defn- handle-put-values
  [^Put put-op values]
  (doseq [value (partition 2 values)]
    (let [[family qv-pairs] value]
      (doseq [[q v] (partition 2 qv-pairs)]
        (put-add put-op family q v))))
  put-op)

(defn- handle-put-values-ts
  [^Put put-op ts values]
  (doseq [value (partition 2 values)]
    (let [[family qv-pairs] value]
      (doseq [[q v] (partition 2 qv-pairs)]
        (put-add-ts put-op ts family q v))))
  put-op)

(defn- handle-put-with-ts
  "Expects spec to be of the form [:with-timestamp ts [...]], where the ellipses
   are any number of :value or :values specs."
  [^Put put-op spec]
  (let [[_ ts sub-specs] spec
        sub-specs (partition-query sub-specs put-argnums)]
    (doseq [sub-spec sub-specs]
      (condp = (first sub-spec)
        :value (apply put-add-ts put-op ts (second sub-spec))
        :values (apply handle-put-values-ts put-op ts (second sub-spec))))
    put-op))

(defn put*
  "Returns a Put object suitable for performing a put on an HTable. To make
   modifications to an existing Put object, pass it as the argument to
   :use-existing; to use an existing RowLock, pass it as the argument to
   :row-lock."
  [row & args]
  (let [specs  (partition-query args put-argnums)
        [^Put put-op specs] (make-put row specs)]
    (doseq [spec specs]
      (condp = (first spec)
          :value          (apply put-add put-op (second spec))
          :values         (handle-put-values put-op (second spec))
          :with-timestamp (handle-put-with-ts put-op spec)
          :write-to-WAL   (.setWriteToWAL put-op (second spec))))
    put-op))

(defn put
  "Creates and executes a Put object against the given table. Options are
   the same as for put."
  [^HTableInterface table row & args]
  (let [p ^Put (apply put* row args)]
    (io!
     (.put table p))))

(defn check-and-put
  "Atomically checks that the row-family-qualifier-value match the values we
   give, and if so, executes the Put. A nil value checks for the non-existence
   of the column."
  ([#^HTableInterface table row family qualifier value #^Put put]
     (.checkAndPut table (to-bytes row) (to-bytes family) (to-bytes qualifier)
                   (if (nil? value) nil (to-bytes value))
                   put))
  ([#^HTableInterface table [row family qualifier value] #^Put put]
     (check-and-put table row family qualifier value put)))

(defn check-and-delete
  "Atomically checks that the row-family-qualifier-value match the values we
   give, and if so, executes the Delete. A nil value checks for the
   non-existence of the column."
  ([#^HTableInterface table row family qualifier value #^Delete delete]
     (.checkAndDelete table (to-bytes row) (to-bytes family)
                      (to-bytes qualifier)
                      (if (nil? value) nil (to-bytes value))
                      delete))
  ([#^HTableInterface table [row family qualifier value] #^Delete delete]
     (check-and-delete table row family qualifier value delete)))

(defn insert
  "If the family and qualifier are non-existent, the Put will be committed.
   The row is taken from the Put object, but the family and qualifier cannot
   be determined from a Put object, so they must be specified."
  [#^HTableInterface table family qualifier ^Put put]
  (check-and-put table (.getRow put) family qualifier
                 (byte-array 0) put))

;;
;; DELETE
;;

;; This maps each delete command to its number of arguments, for helping us
;; partition the command sequence.
(def ^{:private true} delete-argnums
  {:column                1    ;; :column [:family-name :qualifier]
   :columns               1    ;; :columns [:family-name [:q1 :q2...]...]
   :family                1    ;; :family :family-name
   :families              1    ;; :families [:family1 :family2 ...]
   :with-timestamp        2    ;; :with-timestamp <long> [:column [...] ...]
   :with-timestamp-before 2    ;; :with-timestamp-before <long> [:column [...] ...]
   :all-versions          1    ;; :all-versions [:column [:cf :cq]
                               ;;                :columns [:cf [...]] ...]
   :use-existing          1})  ;; :use-existing <a Put you've made>

(defn- make-delete
  "Makes a Delete object, taking into account user construction directives,
   such as using an existing Delete, or passing a pre-existing RowLock.
   Returns a two-item vector, with the Delete object returned in the first
   element, and the remaining, non-construction options in the second."
  [row options]
  (let [row (to-bytes row)
        directives #{:use-existing}
        cons-opts (apply hash-map (flatten (filter
                                            #(contains? directives (first %))
                                            options)))
        delete-obj (cond (contains? cons-opts :use-existing)
                         (io! (:use-existing cons-opts))
                         :else (new Delete row))]
    [delete-obj (filter #(not (contains? directives (first %))) options)]))

(defn- delete-column
  [^Delete delete-op family qualifier]
  (.deleteColumn delete-op (to-bytes family) (to-bytes qualifier)))

(defn- delete-columns
  [^Delete delete-op family qualifier]
  (.deleteColumns delete-op (to-bytes family) (to-bytes qualifier)))

(defn- delete-column-with-timestamp
  [^Delete delete-op family qualifier timestamp]
  (.deleteColumn delete-op (to-bytes family) (to-bytes qualifier) timestamp))

(defn- delete-column-before-timestamp
  [^Delete delete-op family qualifier timestamp]
  (.deleteColumns delete-op (to-bytes family) (to-bytes qualifier) timestamp))

(defn- delete-family
  [^Delete delete-op family]
  (.deleteFamily delete-op (to-bytes family)))

(defn- delete-family-timestamp
  [^Delete delete-op family timestamp]
  (.deleteFamily delete-op (to-bytes family) timestamp))

(defn- handle-delete-ts
  [^Delete delete-op ts-specs]
  (doseq [[ts-op timestamp specs] (partition 3 ts-specs)
          spec (partition-query specs delete-argnums)]
    (condp = ts-op
      :with-timestamp
      (condp = (first spec)
        :column
        (apply #(delete-column-with-timestamp delete-op %1 %2 timestamp)
               (first (rest spec)))
        :columns (let [[family quals] (first (rest spec))]
                   (doseq [q quals]
                     (delete-column-with-timestamp
                      delete-op family q timestamp))))
      :with-timestamp-before
      (condp = (first spec)
        :column
        (apply #(delete-column-before-timestamp delete-op %1 %2 timestamp)
               (first (rest spec)))
        :columns (let [[family quals] (first (rest spec))]
                   (doseq [q quals]
                     (delete-column-before-timestamp
                      delete-op family q timestamp)))
        :family (delete-family-timestamp delete-op (second spec) timestamp)
        :families (doseq [f (rest spec)]
                    (delete-family-timestamp delete-op f timestamp))))))

(defn- delete-all-versions
  [^Delete delete-op specs]
  (doseq [spec (partition-query specs delete-argnums)]
    (condp = (first spec)
      :column
      (apply #(delete-columns delete-op %1 %2) (first (rest spec)))
      :columns (let [[family quals] (first (rest spec))]
                 (doseq [q quals]
                   (delete-columns delete-op family q))))))

(defn delete*
  "Returns a Delete object suitable for performing a delete on an HTable. To
   make modifications to an existing Delete object, pass it as the argument to
   :use-existing; to use an existing RowLock, pass it as the argument to
   :row-lock."
  [row & args]
  (let [specs     (partition-query args delete-argnums)
        [^Delete delete-op specs] (make-delete row specs)]
    (doseq [spec specs]
      (condp = (first spec)
          :with-timestamp        (handle-delete-ts delete-op spec)
          :with-timestamp-before (handle-delete-ts delete-op spec)
          :all-versions          (delete-all-versions delete-op (second spec))
          :column                (apply #(delete-column delete-op %1 %2)
                                        (second spec))
          :columns               (apply-columns #(delete-column delete-op %1 %2)
                                                (second spec))
          :family                (delete-family delete-op (second spec))
          :families              (doseq [f (rest spec)]
                                   (delete-family delete-op f))))
    delete-op))

(defn delete
  "Creates and executes a Delete object against the given table. Options are
   the same as for delete."
  [#^HTableInterface table row & args]
  (let [d #^Delete (apply delete* row args)]
    (io!
     (.delete table d))))

;;
;; SCAN
;;

;; This maps each scan command to its number of arguments, for helping us
;; partition the command sequence.
(def ^{:private true} scan-argnums
  {:column       1    ;; :column [:family-name :qualifier]
   :columns      1    ;; :columns [:family-name [:qual1 :qual2...]...]
   :family       1    ;; :family :family-name
   :families     1    ;; :families [:family1 :family2 ...]
   :filter       1    ;; :filter <a filter you've made>
   :all-versions 0    ;; :all-versions
   :max-versions 1    ;; :max-versions <int>
   :time-range   1    ;; :time-range [start end]
   :time-stamp   1    ;; :time-stamp time
   :start-row    1    ;; :start-row row
   :stop-row     1    ;; :stop-row row
   :use-existing 1})  ;; :use-existing <some Get you've made>

(defn- make-scan
  [options]
  (let [directives #{:use-existing}
        cons-opts (apply hash-map (flatten (filter
                                            #(contains? directives (first %))
                                            options)))
        scan-obj (cond (contains? cons-opts :use-existing)
                       (io! (:use-existing cons-opts))
                       :else
                       (Scan.))]
    [scan-obj (filter #(not (contains? directives (first %))) options)]))

(defn scan*
  "Returns a Scan object suitable for performing a scan on an HTable. To make
   modifications to an existing Scan object, pass it as the argument to
   :use-existing."
  [& args]
  (let [specs   (partition-query args scan-argnums)
        [^Scan scan-op specs] (make-scan specs)]
    (doseq [spec specs]
      (condp = (first spec)
          :column       (apply #(.addColumn scan-op
                                            (to-bytes %1) (to-bytes %2))
                               (second spec))
          :columns      (apply-columns #(.addColumn scan-op
                                                    (to-bytes %1) (to-bytes %2))
                                       (second spec))
          :family       (.addFamily scan-op (to-bytes (second spec)))
          :families     (doseq [f (second spec)]
                          (.addFamily scan-op (to-bytes f)))
          :filter       (.setFilter scan-op (second spec))
          :all-versions (.setMaxVersions scan-op)
          :max-versions (.setMaxVersions scan-op (second spec))
          :time-range   (apply #(.setTimeRange scan-op %1 %2) (second spec))
          :time-stamp   (.setTimeStamp scan-op (second spec))
          :start-row    (.setStartRow scan-op (to-bytes (second spec)))
          :stop-row     (.setStopRow scan-op (to-bytes (second spec)))))
    scan-op))

(defn scan
  "Creates and runs a Scan object. All arguments are the same as scan.
   ResultScanner implements Iterable, so you should be able to just use it
   directly, but don't forget to .close it! Better yet, use with-scanner."
  [#^HTableInterface table & args]
  (let [s (apply scan* args)]
    (io!
     (scanner table s))))
