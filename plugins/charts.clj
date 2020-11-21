(ns plugins.charts
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as clj-str]
            [clojure.spec.alpha :as s]
            [spec-tools.core :as st]
            [clojure.tools.logging :as log]
            [tservice.config :refer [get-workdir get-proxy-server get-plugin-dir]]
            [tservice.events :as events]
            [tservice.lib.fs :as fs-lib]
            [tservice.lib.filter-files :as ff]
            [tservice.db.handler :as db-handler]
            [tservice.util :as u]
            [clj-filesystem.core :as clj-fs]
            [clojure.java.io :as io]
            [selmer.parser :refer [render-file set-resource-path!]]))

;;; ------------------------------------------------ Utility ------------------------------------------------
(defn set-template-dir!
  []
  (set-resource-path! (fs-lib/join-paths (get-plugin-dir) "charts")))

(set-template-dir!)

(defn guess-separator
  [header]
  (let [seps [\tab \, \; \space]
        sep-map (->> (map #(hash-map % (count (clj-str/split header (re-pattern (str %))))) seps)
                     (into {}))]
    (key (apply max-key val sep-map))))

(defn get-cols
  [filepath]
  (let [{:keys [protocol bucket prefix]} (ff/parse-path filepath)]
    (clj-fs/with-conn protocol
      (let [reader (io/reader (clj-fs/get-object bucket prefix))
            header (first (line-seq reader))
            separator (guess-separator header)]
        (clj-str/split header (re-pattern (str separator)))))))

(defn write-json
  [json dest]
  (with-open [wrtr (io/writer dest)]
    (.write wrtr (json/write-str json))))

(defn update-json-file
  [src dest newMap]
  (-> (json/read (io/reader src) :key-fn keyword)
      (u/deep-merge newMap)
      (write-json dest)))

(defn detect-graph
  [graph-dir]
  (filter #(fs-lib/directory? (fs-lib/join-paths graph-dir %))
          (fs-lib/children graph-dir)))

(defn make-graph-metadata
  [graph-dir]
  (->> (detect-graph graph-dir)
       (map (fn [graph] {:name graph :schema map?}))))

;;; ------------------------------------------------ Event Specs ------------------------------------------------
(defn gen-route
  [route-name schema]
  [(str "/graph/" route-name)
   {:tags ["Graph"]
    :post {:summary (str "Create a graph for " route-name)
           :parameters {:body schema}
           :responses {201 {:body {:access_url string? :log_url string?}}}
           :handler (fn [{{:keys [body]} :parameters}]
                      (let [graph-name (clj-str/join "" [route-name "-" (u/rand-str 10)])
                            workdir (get-workdir "Graph")]
                        (events/publish-event! :graph {:name graph-name :parameters body :proxy-server-dir workdir})
                        {:status 201
                         :body {:access_url (fs-lib/join-paths (get-proxy-server) graph-name)
                                :log_url ""}}))}}])

(s/def ::filepath
  (st/spec
   {:spec                (s/and string? #(re-matches #"^(oss|s3|minio):\/\/.*" %))
    :type                :string
    :description         "File path for graph."
    :swagger/default     nil
    :reason              "The filepath must be string."}))

(def ui-schema-query-params
  "A spec for the body parameters."
  (s/keys :req-un []
          :opt-un [::filepath]))

(defn gen-ui-schema-route
  [route-name]
  [(str "/graph/" route-name "/schema")
   {:tags ["Graph Schema"]
    :get {:summary (str "Get ui schema for the graph")
          :parameters {:query ui-schema-query-params}
          :responses {200 {:body map?}}
          :handler (fn [{{{:keys [filepath]} :query} :parameters}]
                     (let [cols (get-cols filepath)
                           schema-file  (str route-name "/" "ui-schema.json.tmpl")
                           columnOptions (vec (map (fn [col] {"value" col "label" col}) cols))
                           json-str (render-file schema-file {:columnOptions columnOptions})]
                       (log/info json-str)
                       {:status 200
                        :body (json/read-str json-str)}))}}])

(defn gen-manifest
  [route-name]
  {:description (str "Graph Builder for " route-name)
   :category "Graph"
   :home "https://github.com/clinico-omics/tservice-plugins"
   :name (str "Build Interactive Graph/Plot for " route-name)
   :source "PGx"
   :short_name route-name
   :icons [{:src "", :type "image/png", :sizes "192x192"}
           {:src "", :type "image/png", :sizes "192x192"}]
   :author "Jingcheng Yang"
   :hidden false
   :id "a4314e073061b74dc16e4c6a9dcd3999"
   :app_name (str "yangjingcheng/" route-name)})

;;; ------------------------------------------------ Event Metadata ------------------------------------------------
;; Schema Example -- {:data {:dataType "" :dataFile ""} :attributes {:xAxis "" ...}}
;; Graph List -- (def charts [{:name "boxplot-r" :schema map?} {:name "corrplot-r" :schema map?}])
(def charts (make-graph-metadata (fs-lib/join-paths (get-plugin-dir) "charts")))

(def metadata
  {:routes (concat (map #(gen-route (:name %) (:schema %)) charts)
                   (map #(gen-ui-schema-route (:name %)) charts))
   :manifests (map gen-manifest ["boxplot-r" "corrplot-r"])})

(def ^:const graph-topics
  "The `Set` of event topics which are subscribed to for use in graph tracking."
  #{:graph})

(def ^:private graph-channel
  "Channel for receiving event graph we want to subscribe to for graph events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------
;;; Parameters Example: {:data {:dataType "" :dataFile ""} :attributes {:xAxis "" ...}}
(defn- graph! [name parameters proxy-server-dir]
  (log/info "Build a graph: " name parameters)
  (let [graph (first (clj-str/split name #"-[a-z0-9]+$"))
        relative-dir name
        dest-dir (fs-lib/join-paths proxy-server-dir relative-dir)
        log-path (fs-lib/join-paths dest-dir "log")
        graph-dir (fs-lib/join-paths (get-plugin-dir) "charts" graph)
        data-file (:dataFile (:data parameters))]
    ;; TODO: Re-generate config file for graph
    ;; All R packages are soft links when renv enable cache, 
    (fs-lib/copy-recursively graph-dir dest-dir {:nofollow-links true})
    (when ((comp not empty?) parameters)
      (ff/copy-files! [data-file] dest-dir {:nofollow-links true})
      (update-json-file (fs-lib/join-paths graph-dir "config.json")
                        (fs-lib/join-paths dest-dir "config.json")
                        (u/deep-merge parameters {:data {:dataFile (ff/basename data-file)}})))
    (spit log-path (json/write-str {:status "Success" :msg ""}))
    (db-handler/create-report! name (str "Make a " graph) nil graph relative-dir "Graph")))

(defn- process-graph-event!
  "Handle processing for a single event notification received on the graph-channel"
  [graph-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} graph-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "graph" (graph! (:name object) (:parameters object) (:proxy-server-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process graph event. %s" (:topic graph-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for graph events."
  []
  (events/start-event-listener! graph-topics graph-channel process-graph-event!))
