(ns stow.db
  (:require
   [babashka.pods :as pods]
   [stow.auth :as auth]
   #?(:bb [babashka.fs :as fs])
   [stow.cipher :as cipher]
   [stow.serialize :refer [prn-data read-data]]))


;; Specifying pods in bb.edn does not work in libraries which are
;; used into another library. i.e. no transitive pods at cur time.
(pods/load-pod 'org.babashka/go-sqlite3 "0.1.0")

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
  [parent key]
  (if (seq (query
            (substitute
             "SELECT id FROM node WHERE parent=$p AND key=$k"
             {"$p" (prep parent)
              "$k" (prep key)})))
    true
    false))


(def ^:private add-node-error
  (str "When adding a node, please specify both a :key and a :value.\n"
       "You can additionally specify a :parent. e.g.\n"
       "  :add-node :key facebook.com :value 7\n"
       "  :add-node :parent :work :key facebook.com"
       " :value {:user mike :pwd '66%$hfhU77^'}"))


(defn- add-node*
  "Adds a node to the db. The keys :key and :value should be specified.
   Optionally a :parent can be specified. If it is not, it's defaulted to :root
   Checks if the key already exists under the parent, and if
   :skip? is true returns nil, otherwise throws an error."
  [& {:keys [key value parent skip?]
      :or {parent :root skip? false}}]
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
            (if (= :root parent)
              {:id nid :parent :root}
              {:id nid})))

        (if skip?
          :not-inserted
          (throw (ex-info (str "That key already exists under parent-id " parent) {})))))))


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


(defn get-nodes-by-parent
  "Lists the keys of the nodes under the parent."
  [parent & {:keys [decrypt?] :or {decrypt? true}}]
  (mapv
   (fn [n] (if decrypt? (decode n) n))
   (query
    (substitute
     "SELECT id, key FROM node WHERE parent=$p"
     {"$p" (prep parent)}))))


(defn get-nodes-by-key
  "Returns nodes with key equal to k."
  [k & {:keys [decrypt?] :or {decrypt? true}}]
  (map
   (fn [n] (if decrypt? (decode n) n))
   (query
    (substitute
     "SELECT * FROM node WHERE key=$k"
     {"$k" (prep k)}))))


(defn get-node
  "Returns the node specified by the parent and key."
  [parent k & {:keys [decrypt?] :or {decrypt? true}}]
  (first
   (map
    (fn [n] (if decrypt? (decode n) n))
    (query
     (substitute
      "SELECT * FROM node WHERE key=$k AND parent=$p"
      {"$k" (prep k)
       "$p" (prep parent)})))))


(defn get-all-nodes
  "Return all nodes. :decrypt? indicates whether the keys and values
  should be decrypted."
  [& {:keys [decrypt?] :or {decrypt? false}}]
  (let [data (query "SELECT * FROM node")]
    (if decrypt?
      (map decode data)
      data)))


(defn get-parent-ids
  "Returns parents as a list."
  []
  (map
   :parent
   (query
    "SELECT DISTINCT parent FROM node")))


(defn list-keys
  "Returns a list of tuples; parent and key."
  []
  (map
   (juxt :parent :key)
   (get-all-nodes :decrypt? true)))


(defn- update-value
  "Defines how new-val is used to update old-val"
  [old-val new-val]
  (cond
    (and (map? old-val) (map? new-val))
    (merge old-val new-val)

    (and (seq? old-val) (seq? new-val))
    (concat old-val new-val)

    (and (vector? old-val) (vector? new-val))
    (into [] (concat old-val new-val))

    (and (set? old-val) (set? new-val))
    (clojure.set/union old-val new-val)

    (and (sequential? old-val) (sequential? new-val))
    (concat old-val new-val)

    ;; end of the easy decisions!
    :else new-val))


(defn update-node-with
  "Updates the value of the node specified by parent & k, by applying
   f to old value and args."
  [parent k f & args]
  (if-let [old-node (get-node parent k)]
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


(defn update-node
  "Attempts to combine new-value with the existing value specified
   by parent and k. If old-value and new-value are both Clojure collections,
   they are merged/ concat'd or unioned depending on the type of collection. If one or
   other is not a collection or they are not collections of compatible types,
   then new-value simply replaces the old-value."
  [parent k new-value]
  (update-node-with parent k (partial update-value) new-value))


(defn delete-node
  "Deletes the node specified by parent & k."
  [parent k]
  (exec!
   (substitute
    "DELETE FROM node WHERE parent=$p AND key=$k"
    {"$p" (prep parent)
     "$k" (prep k)})))


(defn previous-versions
  "Returns historic versions of the node specified by parent and key.
   :decrypt? indicates whether the keys and values should be decrypted."
  [parent key & {:keys [decrypt?] :or {decrypt? false}}]
  (map
    (fn [n] (if decrypt? (decode n) n))
    (query
     (substitute
      "SELECT * FROM node_history WHERE key=$k AND parent=$p"
      {"$k" (prep key)
       "$p" (prep parent)}))))


(defn- max-version [nodes]
  (apply max (map :version nodes)))


(defn restore
  "Restores the value from the last version of a node specified by parent and key, or
   if a version is also specified, restores the value from that version."
  ([parent key]
   (if-let [historic-nodes (seq (previous-versions parent key))]
     (restore parent key (max-version historic-nodes))
     (throw (ex-info "Node specified by parent and key doesn't exist in history." {}))))
  
  ([parent key version]
   (if-let [historic-node (first (filter #(= version (:version %)) (previous-versions parent key)))]
     (let [historic-value (:value (decode historic-node))]
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
