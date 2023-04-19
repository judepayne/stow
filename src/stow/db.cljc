(ns stow.db
  (:require
   [babashka.pods :as pods]
   [stow.auth :as auth]
   #?(:bb [babashka.fs :as fs])
   [stow.cipher :as cipher]
   [clojure.edn :refer [read-string]]
   [stow.serialize :refer [prn-data read-data]]))


;; Specifying pods in bb.edn does not work in libraries which are
;; used into another library. i.e. no transitive pods at cur time.
(pods/load-pod 'org.babashka/go-sqlite3 "0.1.2")


(require '[pod.babashka.go-sqlite3 :as sqlite])


;; conn
(def ^:private stow-path (atom nil))


(defn set-stow-path [p] (reset! stow-path p))


(defn- get-stow-path [] @stow-path)


;; db
(defn- exec! [cmd]
  (if @stow-path
    (sqlite/execute! @stow-path cmd)
    (throw (ex-info "Can't execute a command against a `nil` db." {}))))


(defn- query [cmd]
  (if @stow-path
    (sqlite/query @stow-path cmd)
    (throw (ex-info "Can't run a query against a `nil` db." {}))))


(defn- substitute
  "Substitutes map of replacements in the string."
  [s & {:as replacements}]
  (let [subs (reduce-kv clojure.string/replace s replacements)]
    subs))


(defn- as-str
  "Wrap s in single quotes."
  [s] (str "'" s "'"))


(defn- de-str
  "Remove leading and trailing chars"
  [s] (subs s 1 (dec (count s))))


;; config
(defn store-config
  [k v]
  (exec!
   (substitute
    "INSERT INTO config (name,value) VALUES ($name, $value) ON CONFLICT(name)
     DO UPDATE SET value=excluded.value"
    {"$name" (as-str k)
     "$value" (as-str v)})))


(defn fetch-config
  [k]
  (->
   (query
    (substitute
     "SELECT value FROM config WHERE name=$name"
     {"$name" (as-str k)}))
   first
   :value))


(defn delete-config
  [k]
  (exec!
   (substitute
    "DELETE FROM config WHERE name=$name"
    {"$name" (as-str k)})))


(defn- fetch-salt [] (fetch-config "salt"))


(defn- store-salt [salt] (store-config "salt" salt))


(defn- fetch-main-hash [] (fetch-config "main-hash"))


(defn- store-main-hash [salt] (store-config "main-hash" salt))


(defn- fetch-def-parent []
  (when-let [stored (fetch-config "default-parent")]
    (read-string stored)))


(defn- store-def-parent [def-parent] (store-config "default-parent" def-parent))


(defn- default-parent []
  (or (fetch-def-parent) :root))


;; nodes

(defn- encrypt [item]
  (cipher/encrypt item @auth/passphrase (fetch-salt)))


(defn- decrypt [encrypted]
  (cipher/decrypt encrypted @auth/passphrase (fetch-salt)))


;; processing chains for in and out of db
(def ^:private prep (comp as-str encrypt prn-data))


(def ^:private serve (comp read-data decrypt))


(defn- map-vals
  "Applies f to the values in m, if the key is one of the keys."
  [m f & keys]
  (let [keys (into #{} keys)]
    (into {}
          (map (fn [[k v]]
                 (if (contains? keys k)
                   [k (f v)]
                   [k v]))
               m))))


(defn- encode [m] (map-vals m prep :parent :key :value))


(defn- decode [m] (map-vals m serve :parent :key :value))


(defn- valid-node?
  "Validates that a node has :k and :v keys."
  [node]
  (every? node #{:key :value}))


(defn- node-exists?
  ([key] (node-exists? (default-parent) key))
  ([parent key]
   (if (seq (query
             (substitute
              "SELECT id FROM node WHERE parent=$p AND key=$k"
              {"$p" (prep parent)
               "$k" (prep key)})))
     true
     false)))


(def ^:private add-node-error
  (str "When adding a node, please specify both a :key and a :value.\n"
       "You can additionally specify a :parent. e.g.\n"
       "  :add-node :key facebook.com :value 7\n"
       "  :add-node :parent :work :key facebook.com"
       " :value {:user mike :pwd \"66%$hfhU77^\"}"))


(defn- add-node*
  "Adds a node to the db. The keys :key and :value should be specified.
   Optionally a :parent can be specified. If it is not, it's defaulted to the
   current `default-parent` (or `:root` if that is not set).
   Checks if the key already exists under the parent, and if
   :skip? is true returns nil, otherwise throws an error."
  [& {:keys [key value parent skip?]
      :or {parent (default-parent) skip? false}}]
  (if (not (and key value))
    (throw (ex-info add-node-error {}))
    (let [{p :parent k :key v :value} (encode {:key key :value value :parent parent})]

      ;; sqllite doesn't allows constraints on two columns, so ...
      (if (not (node-exists? parent key))

        (do
          (exec!
           (substitute
            "INSERT INTO node (parent, key, value) VALUES ($p, $k, $v)"
            {"$p" p
             "$k" k
             "$v" v}))
          (let [nid (-> (query
                         (substitute
                          "SELECT id FROM node WHERE parent=$p AND key=$k"
                          {"$p" p
                           "$k" k}))
                        first
                        :id)]
            {:id nid :parent parent}))

        (let [msg (str ":parent " parent ", :key " key " already exists. Didn't insert " value)]
          (if skip?
            msg
            (throw (ex-info msg {}))))))))


(defn add-node
  "Adds a node to the db, taking either :parent, :key and :value or just :key
   and :value in which case :parent is defaulted to :root."
  ([key value]
   (add-node* :key key :value value))
  ([parent key value]
   (add-node* :parent parent :key key :value value)))


(defn add-nodes
  "Adds multiple nodes into the database. ms is a sequence of (node) maps.
  Each map must contain the keys :key, :value and optionally :parent."
  [& nodes]
  ;; check all maps contain required keys
  (when (some (complement valid-node?) nodes)
    (throw (ex-info "Not all nodes contain required keys :key & :value" {})))
    
  ;; insert into the db
  (map (fn [n]
         (let [[k v p] (map n [:key :value :parent])]
           (if p
             (add-node* :key k :value v :parent p :skip? true)
             (add-node* :key k :value v :skip? true))))
       nodes))


(defn add-multi
  "Adds multiple nodes specified by kvs (a list of keys and their values) under
   the parent. e.g. `add-multi facebook :user jude :pwd 6%%fdgWo`."
  [parent & kvs]
  (let [kvs (partition 2 kvs)
        nodes (map
               (fn [[k v]] {:parent parent :key k :value v})
               kvs)]
    (apply add-nodes nodes)))


(defn get-nodes-by-parent
  "Lists the keys of the nodes under the parent."
  [parent & {:keys [decrypt?] :or {decrypt? true}}]
  (mapv
   (fn [n] (if decrypt? (decode n) n))
   (query
    (substitute
     "SELECT * FROM node WHERE parent=$p"
     {"$p" (prep parent)}))))


(defn get-parent
  "Get all nodes belonging to the parent and collapses keys and values into
   one map for convenience."
  [parent]
  (when-let [nodes (seq (get-nodes-by-parent parent))]
    (-> (reduce
         (fn [acc m]
           (assoc acc (:key m) (:value m)))
         {}
         nodes)
        (assoc :parent parent))))


(defn change-parent-name
  "Updates all nodes with parent equal to old-parent to new-parent."
  [old-parent new-parent]
  (exec!
       (substitute
        "UPDATE node SET parent=$np WHERE parent=$op"
        {"$op" (prep old-parent)
         "$np" (prep new-parent)})))


(defn get-nodes-by-key
  "Returns nodes with key."
  [key & {:keys [decrypt?] :or {decrypt? true}}]
  (map
   (fn [n] (if decrypt? (decode n) n))
   (query
    (substitute
     "SELECT * FROM node WHERE key=$k"
     {"$k" (prep key)}))))


(defn- get-node*
  "Returns the node specified by the parent and key."
  [& {:keys [parent key]
      :or {parent (default-parent)}}]
  (first
   (map
    decode
    (query
     (substitute
      "SELECT * FROM node WHERE key=$k AND parent=$p"
      {"$k" (prep key)
       "$p" (prep parent)})))))


(defn get-node
  "Returns the decrypted node specified by either just the key (in which case
   the default parent is used - if set, or `:root` if not) or by both the parent
   and the key."
  ([key] (get-node* {:key key}))
  ([parent key] (get-node* {:parent parent :key key})))


(defn get-all-nodes
  "Return all nodes. :decrypt? indicates whether the keys and values
  should be decrypted."
  [& {:keys [decrypt?] :or {decrypt? false}}]
  (let [data (query "SELECT * FROM node")]
    (if decrypt?
      (map decode data)
      data)))


(defn- get-in-node*
  "Returns the (nested) value in the node specified by parent key and keys."
  [& {:keys [parent key keys]
      :or {parent (default-parent)}}]
  (let [v (:value (get-node parent key))]
    (if keys
      (get-in v keys)
      v)))


(defn get-in-node
  "Returns the decrypted value of the node specified by either just the key (in which case
   the default parent is used - if set, or `:root` if not) or by both the parent and the
   key. Keys is an optional sequence of keys. If it is specified returns the nested value
   specified by keys.
   Example usage: get-in-node :my-parent :secrets [:confidential :password]"
  ([key keys] (get-in-node* {:key key :keys keys}))
  ([parent key keys] (get-in-node* {:key key :keys keys :parent parent})))


(defn get-parents
  "Returns parents as a list. If a regex is specified (as a string, e.g.: \"abc.*\")
   filters the parents to only those that match that the regex matches."
  ([] (get-parents nil))
  ([regex]
   (let [parents 
         (sort
          (map
           (comp serve :parent)
           (query
            "SELECT DISTINCT parent FROM node")))]
     (if regex
       (let [r (re-pattern regex)]
         (filter
          (fn [parent] (re-find r parent))
          parents))
       parents))))


(defn count-nodes
  "Returns the count of nodes."
  []
  (count (get-all-nodes)))


(defn count-parents
  "Returns the count of parents."
  []
  (count (get-parents)))


(defn list-keys
  "Returns a list of tuples; parent and key."
  []
  (map
   (juxt :parent :key)
   (get-all-nodes :decrypt? true)))


(defn- update-node-with*
  [parent key f & args]
  (if-let [old-node (get-node parent key)]
    (let [old-value (:value old-node)
          new-value (try (apply f old-value args)
                         (catch Exception e
                           (throw (ex-info "Couldn't apply the update function." {}))))]
      (exec!
       (substitute
        "UPDATE node SET value=$v WHERE parent=$p AND key=$k"
        {"$v" (prep new-value)
         "$p" (prep (:parent old-node))
         "$k" (prep (:key old-node))})))
    (throw (ex-info "Node specified by parent and key doesn't exist." {}))))


(defn- not-fn-or-var? [i] (not (or (fn? i) (var? i))))


;; bit of a hack to overload with two signatures with variadic args.
(defn update-node-with
  "Updates the value of the node specified by parent & key, by applying
   f to old value and args."
  {:arglists '([key f & args][parent key f & args])}
  [& args]
  (let [firsts (take-while not-fn-or-var? args)]
    (case (count firsts)
      1       (apply update-node-with* (cons (default-parent) args))
      
      2       (apply update-node-with* args)

      (throw (ex-info (str "update-node-with must have either key or parent and key "
                           "before the function.") {})))))


(defn- last-arg [& args] (last args))


(defn update-node
  "Updates the value of the node specified by just the key (in which case
   the default parent is used - if set, or `:root` if not) or by both the parent
   and the key with new-value."
  ([key new-value] (update-node (default-parent) key new-value))
  ([parent key new-value]
   (update-node-with parent key last-arg new-value)))


(defn delete-node
  "Deletes the node specified by just the key (in which case
   the default parent is used - if set, or `:root` if not) or by both the parent
   and the key."
  ([key] (delete-node (default-parent) key))
  ([parent key]
   (let [{:keys [rows-affected last-inserted-id] :as result}
         (exec!
          (substitute
           "DELETE FROM node WHERE parent=$p AND key=$k"
           {"$p" (prep parent)
            "$k" (prep key)}))]
     (if (zero? rows-affected)
       (throw (ex-info "Node specified by parent and key doesn't exist." {}))
       result))))


(defn delete-parent
  "Deletes all nodes are the specified parent."
  [parent]
  (let [{:keys [rows-affected last-inserted-id] :as result}
        (exec!
         (substitute
          "DELETE FROM node WHERE parent=$p"
          {"$p" (prep parent)}))]
    (if (zero? rows-affected)
      (throw (ex-info "No nodes with that parent." {}))
       result)))


(defn previous-versions
  "Returns historic versions of the node specified by just the key (in which case
   the default parent is used - if set, or `:root` if not) or by both the parent
   and the key."
  ([key] (previous-versions (default-parent) key))
  ([parent key]
   (map
    decode
    (query
     (substitute
      "SELECT * FROM node_history WHERE key=$k AND parent=$p"
      {"$k" (prep key)
       "$p" (prep parent)})))))


(defn- max-version [nodes]
  (apply max (map :version nodes)))


(defn restore
  "Restores the value from the last version of a node specified by key (with parent defaulted),
   or parent and key, or if a version is also specified, restores the value from that version."
  ([key] (restore (default-parent) key))
  
  ([parent key]
   (if-let [historic-nodes (seq (previous-versions parent key))]
     (restore parent key (max-version historic-nodes))
     (throw (ex-info "Node specified by parent and key doesn't exist in history." {}))))
  
  ([parent key version]
   (if-let [historic-node (first (filter #(= version (:version %)) (previous-versions parent key)))]
     (let [historic-value (:value historic-node)]
       (if (node-exists? parent key)
         (update-node parent key historic-value)
         (add-node parent key historic-value)))
     
     (throw (ex-info "That version of the node doesn't exist." {})))))


;; initialization


(defn- db-exists? [path]
  #?(:bb (fs/exists? path)
     :clj (.exists (clojure.java.io/file path))))



(defn valid-db? [path]
  (and (db-exists? path)
       (fetch-main-hash)
       #_(seq (get-all-nodes))))  ;; cipher initialized. may not have nodes


(defn- state-db [path]
  (let [old-path @stow-path]
    (reset! stow-path path)
    (let [state (cond
                  (valid-db? path)   :valid
                  (db-exists? path)  :empty
                  :else              :doesnt-exist)]
      (reset! stow-path old-path)
      state)))


(declare create-db)


(defn- init-cipher [path password]
  (let [master-hash (auth/init! auth/pwd-authenticator password)
        salt (cipher/salt)]
      (store-salt salt)
      (store-main-hash master-hash)))


(defn- try-switch-to-db [path password]
  (let [old-path @stow-path]
    (reset! stow-path path)
    (let [main-hash (fetch-main-hash)]
      (if (auth/verify auth/pwd-authenticator password main-hash)
        (some? (reset! auth/passphrase password))
        (do (reset! stow-path old-path)
            false)))))


(defn init
  "Switches to or creates the encrypted store at the supplied path.
   The password is used as the master password for the whole store, e.g.
   as the key for encryption and decryption, and for all future authentication attempts.
   Returns true if successful (e.g. could authenticate against existing db or
   could create new db), otherwise false."
  [path password]
  (let [state (state-db path)]
    (case state
      :valid          (try-switch-to-db path password)
      
      :empty          (some? (and
                              (reset! stow-path path)
                              (init-cipher path password)))
      
      :doesnt-exist   (some? (and
                              (create-db path)
                              (reset! stow-path path)
                              (init-cipher path password))))))


(defn logout
  "Resets stow-path to nil"
  [] (reset! stow-path nil))


(defn- create-db [path]
  (sqlite/execute!
   path
   "CREATE TABLE IF NOT EXISTS config (
      name TEXT NOT NULL,
      value TEXT NOT NULL
    );;
    CREATE UNIQUE INDEX IF NOT EXISTS ids_config_name ON config(name);;

    CREATE TABLE IF NOT EXISTS node (
      id INTEGER PRIMARY KEY,
      parent BLOB NOT NULL,
      key BLOB NOT NULL,
      value BLOB,
      version INTEGER DEFAULT 1,
      created TEXT DEFAULT CURRENT_TIMESTAMP,
      modified TEXT DEFAULT CURRENT_TIMESTAMP
    );;
    CREATE INDEX IF NOT EXISTS idx_node_parent ON node(parent);;

    CREATE TABLE IF NOT EXISTS node_history (
      id INTEGER NOT NULL,
      parent BLOB,
      key BLOB NOT NULL,
      value BLOB NOT NULL,
      version INTEGER NOT NULL,
      created TEXT NOT NULL,
      modified TEXT NOT NULL
    );;
    CREATE INDEX IF NOT EXISTS idx_node_history_id_modified ON node(id, modified);;

    CREATE TRIGGER IF NOT EXISTS trigger_node_on_value_update
    AFTER UPDATE OF value ON node FOR EACH ROW
    BEGIN
      UPDATE node
        SET modified = CURRENT_TIMESTAMP,
            created = OLD.created,
            version = OLD.version + 1
      WHERE id=NEW.id;

      INSERT INTO node_history (id, parent, key, value, version, created, modified) VALUES (
        OLD.id,
        OLD.parent,
        OLD.key,
        OLD.value,
        OLD.version,
        OLD.created,
        OLD.modified
      );

      DELETE FROM node_history WHERE
        id=NEW.id AND
        version <= NEW.version - 10;
    END;;

    CREATE TRIGGER IF NOT EXISTS trigger_node_on_parent_update
    AFTER UPDATE OF parent ON node FOR EACH ROW
    BEGIN
      UPDATE node
        SET modified = CURRENT_TIMESTAMP,
            created = OLD.created,
            version = OLD.version + 1
      WHERE id=NEW.id;

      INSERT INTO node_history (id, parent, key, value, version, created, modified) VALUES (
        OLD.id,
        OLD.parent,
        OLD.key,
        OLD.value,
        OLD.version,
        OLD.created,
        OLD.modified
      );

      DELETE FROM node_history WHERE
        id=NEW.id AND
        version <= NEW.version - 10;
    END;;"))
