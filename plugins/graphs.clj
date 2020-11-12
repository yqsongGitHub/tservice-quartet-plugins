(ns plugins.graphs
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as clj-str]
            [clojure.tools.logging :as log]
            [spec-tools.core :as st]
            [tservice.config :refer [get-workdir get-proxy-server get-plugin-dir]]
            [tservice.events :as events]
            [tservice.lib.fs :as fs-lib]
            [tservice.db.handler :as db-handler]
            [tservice.util :as u]))

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
(def metadata
  {:routes (map #(gen-route (:name %) (:schema %)) [{:name "boxplot-r" :schema map?} {:name "corrplot-r" :schema map?}])
   :manifests (map gen-manifest ["boxplot-r" "corrplot-r"])})

(def ^:const graph-topics
  "The `Set` of event topics which are subscribed to for use in graph tracking."
  #{:graph})

(def ^:private graph-channel
  "Channel for receiving event graph we want to subscribe to for graph events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- graph! [name parameters proxy-server-dir]
  (log/info "Build a graph: " name parameters)
  (let [graph (first (clj-str/split name #"-[a-z0-9]+$"))
        relative-dir name
        dest-dir (fs-lib/join-paths proxy-server-dir relative-dir)
        log-path (fs-lib/join-paths dest-dir "log")
        graph-dir (fs-lib/join-paths (get-plugin-dir) "graphs" graph)]
    ;; TODO: Re-generate config file for graph
    ;; All R packages are soft links when renv enable cache, 
    (fs-lib/copy-recursively graph-dir dest-dir {:nofollow-links true})
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
