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

(defn detect-chart
  [chart-dir]
  (filter #(fs-lib/directory? (fs-lib/join-paths chart-dir %))
          (fs-lib/children chart-dir)))

(defn make-chart-metadata
  [chart-dir]
  (->> (detect-chart chart-dir)
       (map (fn [chart] {:name chart :schema map?}))))

;;; ------------------------------------------------ Event Specs ------------------------------------------------
(defn gen-route
  [route-name schema]
  [(str "/chart/" route-name)
   {:tags ["Chart"]
    :post {:summary (str "Create a chart for " route-name)
           :parameters {:body schema}
           :responses {201 {:body {:access_url string? :log_url string?}}}
           :handler (fn [{{:keys [body]} :parameters}]
                      (let [chart-name (clj-str/join "" [route-name "-" (u/rand-str 10)])
                            workdir (get-workdir "Chart")]
                        (events/publish-event! :chart {:name chart-name :parameters body :proxy-server-dir workdir})
                        {:status 201
                         :body {:access_url (str (clj-str/replace (get-proxy-server) #"/$" "") "/" chart-name)
                                :log_url ""}}))}}])

(s/def ::filepath
  (st/spec
   {:spec                (s/and string? #(re-matches #"^(oss|s3|minio):\/\/.*" %))
    :type                :string
    :description         "File path for chart."
    :swagger/default     nil
    :reason              "The filepath must be string."}))

(def ui-schema-query-params
  "A spec for the body parameters."
  (s/keys :req-un [::filepath]
          :opt-un []))

(defn gen-ui-schema-route
  [route-name]
  [(str "/chart/" route-name "/schema")
   {:tags ["Chart"]
    :get {:summary (str "Get ui schema for the chart")
          :parameters {:query ui-schema-query-params}
          :responses {200 {:body map?}}
          :handler (fn [{{{:keys [filepath]} :query} :parameters}]
                     (let [cols (get-cols filepath)
                           schema-file  (str route-name "/" "ui-schema.json.tmpl")
                           columnOptions (vec (map (fn [col] {"value" col "label" col}) cols))
                           json-str (render-file schema-file {:columnOptions columnOptions})]
                       (log/debug json-str)
                       {:status 200
                        :body (json/read-str json-str)}))}}])

(defn gen-manifest
  [route-name]
  {:description (str "Chart Builder for " route-name)
   :category "Chart"
   :home "https://github.com/clinico-omics/tservice-plugins"
   :name (str "Build Interactive Chart/Plot for " route-name)
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
;; Chart List -- (def charts [{:name "boxplot-r" :schema map?} {:name "corrplot-r" :schema map?}])
(def charts (make-chart-metadata (fs-lib/join-paths (get-plugin-dir) "charts")))

(def metadata
  {:routes (concat (map #(gen-route (:name %) (:schema %)) charts)
                   (map #(gen-ui-schema-route (:name %)) charts))
   :manifests (map gen-manifest ["boxplot-r" "corrplot-r"])})

(def ^:const chart-topics
  "The `Set` of event topics which are subscribed to for use in chart tracking."
  #{:chart})

(def ^:private chart-channel
  "Channel for receiving event chart we want to subscribe to for chart events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------
;;; Parameters Example: {:data {:dataType "" :dataFile ""} :attributes {:xAxis "" ...}}
(defn- chart! [name parameters proxy-server-dir]
  (log/info "Build a chart: " name parameters)
  (let [chart (first (clj-str/split name #"-[a-z0-9]+$"))
        relative-dir name
        dest-dir (fs-lib/join-paths proxy-server-dir relative-dir)
        log-path (fs-lib/join-paths dest-dir "log")
        chart-dir (fs-lib/join-paths (get-plugin-dir) "charts" chart)
        data-file (:dataFile (:data parameters))]
    ;; TODO: Re-generate config file for chart
    ;; All R packages are soft links when renv enable cache, 
    (fs-lib/copy-recursively chart-dir dest-dir {:nofollow-links true})
    (when ((comp not empty?) parameters)
      (ff/copy-files! [data-file] dest-dir {:nofollow-links true})
      (update-json-file (fs-lib/join-paths chart-dir "config.json")
                        (fs-lib/join-paths dest-dir "config.json")
                        (u/deep-merge parameters {:data {:dataFile (ff/basename data-file)}})))
    (spit log-path (json/write-str {:status "Success" :msg ""}))
    (db-handler/create-report! name (str "Make a " chart) nil chart relative-dir "Chart")))

(defn- process-chart-event!
  "Handle processing for a single event notification received on the chart-channel"
  [chart-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} chart-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "chart" (chart! (:name object) (:parameters object) (:proxy-server-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process chart event. %s" (:topic chart-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for chart events."
  []
  (events/start-event-listener! chart-topics chart-channel process-chart-event!))
