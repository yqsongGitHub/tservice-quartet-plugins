(ns plugins.reports.ballgown2exp
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [plugins.libs.commons :as comm]
            [plugins.wrappers.merge-exp :as me]
            [plugins.wrappers.rnaseq2report :as r2r]
            [spec-tools.core :as st]
            [spec-tools.json-schema :as json-schema]
            [tservice.config :refer [get-workdir]]
            [tservice.events :as events]
            [tservice.lib.filter-files :as ff]
            [tservice.lib.fs :as fs-lib]
            [tservice.util :as u]
            [tservice.vendor.multiqc :as mq]))

;;; ------------------------------------------------ Event Specs ------------------------------------------------
(s/def ::sample_id
  (st/spec
   {:spec                string?
    :type                :string
    :description         "Cample id."
    :swagger/default     []
    :reason              "The sample id must a string."}))

(s/def ::group
  (st/spec
   {:spec                string?
    :type                :string
    :description         "The group name which is matched with sample id."
    :swagger/default     []
    :reason              "The group must a string."}))

(s/def ::filepath
  (st/spec
   {:spec                (s/and string? #(re-matches #"^[a-zA-Z0-9]+:\/(\/|\.\/)[a-zA-Z0-9_]+.*" %))
    :type                :string
    :description         "File path for covertor."
    :swagger/default     nil
    :reason              "The filepath must be string."}))

(s/def ::metadat-item
  (s/keys :req-un [::sample_id ::group]))

(s/def ::metadata
  (s/coll-of ::metadat-item))

(s/def ::parameters
  (s/keys :req-un []))

(def ballgown2exp-params-body
  "A spec for the body parameters."
  (s/keys :req-un [::filepath ::metadata ::parameters]))

;;; ------------------------------------------------ Event Metadata -------------------------------------------------
(def metadata
  {:route ["/report/ballgown2exp"
           {:tags ["Report"]
            :post {:summary "Convert ballgown files to experiment table and generate report."
                   :parameters {:body ballgown2exp-params-body}
                   :responses {201 {:body {:results string? :log string? :report string? :id string?}}}
                   :handler (fn [{{{:keys [filepath parameters metadata]} :body} :parameters}]
                              (let [workdir (get-workdir)
                                    from-path (u/replace-path filepath workdir)
                                    uuid (u/uuid)
                                    relative-dir (fs-lib/join-paths "download" uuid)
                                    to-dir (fs-lib/join-paths workdir relative-dir)
                                    log-path (fs-lib/join-paths relative-dir "log")]
                                (fs-lib/create-directories! to-dir)
                                ; Launch the ballgown2exp
                                (spit log-path (json/write-str {:status "Running" :msg ""}))
                                (events/publish-event! :ballgown2exp-convert
                                                       {:data-dir from-path
                                                        :metadata metadata
                                                        :parameters parameters
                                                        :dest-dir to-dir})
                                {:status 201
                                 :body {:results (fs-lib/join-paths relative-dir)
                                        :report (fs-lib/join-paths relative-dir "multiqc.html")
                                        :log log-path
                                        :id uuid}}))}
            :get {:summary "A json shema for ballgown2exp."
                  :parameters {}
                  :responses {200 {:body map?}}
                  :handler (fn [_]
                             {:status 200
                              :body (json-schema/transform ballgown2exp-params-body)})}}]
   :manifest {:description "Convert Ballgown Result Files to Expression Table."
              :category "Report"
              :home "https://github.com/clinico-omics/tservice-plugins"
              :name "Ballgown to Expression Table"
              :source "PGx"
              :short_name "ballgown2exp"
              :icons [{:src "", :type "image/png", :sizes "192x192"}
                      {:src "", :type "image/png", :sizes "192x192"}]
              :author "Jun Shang"
              :hidden false
              :id "65cf2c60567fab94b2afdecaaee13adc"
              :app_name "shangjun/ballgown2exp"}})

(def ^:const ballgown2exp-topics
  "The `Set` of event topics which are subscribed to for use in ballgown2exp tracking."
  #{:ballgown2exp-convert})

(def ^:private ballgown2exp-channel
  "Channel for receiving event ballgown2exp we want to subscribe to for ballgown2exp events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- ballgown2exp!
  "Chaining Pipeline: merge_exp_file -> rnaseq2report -> multiqc."
  [data-dir parameters metadata dest-dir]
  (let [files (ff/batch-filter-files data-dir [".*call-ballgown/.*.txt"])
        ballgown-dir (fs-lib/join-paths dest-dir "ballgown")
        exp-filepath (fs-lib/join-paths dest-dir "fpkm.txt")
        result-dir (fs-lib/join-paths dest-dir "results")
        log-path (fs-lib/join-paths dest-dir "log")
        metadata-file (fs-lib/join-paths dest-dir
                                         "results"
                                         "phenotype.csv")]
    (try
      (fs-lib/create-directories! ballgown-dir)
      (fs-lib/create-directories! result-dir)
      (log/info "Merge these files: " files)
      (log/info "Merge gene experiment files from ballgown directory to a experiment table: " ballgown-dir exp-filepath)
      (ff/copy-files! files ballgown-dir {:replace-existing true})
      (me/merge-exp-files! (ff/list-files ballgown-dir {:mode "file"}) exp-filepath)
      (log/info "Call R2R: " exp-filepath metadata result-dir)
      (comm/write-csv! metadata-file metadata)
      (let [r2r-result (r2r/call-rnaseq2report! exp-filepath metadata-file result-dir)
            multiqc-result (when (= (:status r2r-result) "Success")
                             (mq/multiqc result-dir dest-dir {:title "RNA-seq Report"}))
            result (if multiqc-result (assoc r2r-result
                                             :status (:status multiqc-result)
                                             :msg (str (:msg r2r-result) "\n" (:msg multiqc-result)))
                       r2r-result)
            log (json/write-str result)]
        (log/info "Status: " result)
        (spit log-path log))
      (catch Exception e
        (let [log (json/write-str {:status "Error" :msg (.toString e)})]
          (log/info "Status: " log)
          (spit log-path log))))))

(defn- process-ballgown2exp-event!
  "Handle processing for a single event notification received on the ballgown2exp-channel"
  [ballgown2exp-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} ballgown2exp-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "ballgown2exp"  (ballgown2exp! (:data-dir object) (:parameters object) (:metadata object) (:dest-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process ballgown2exp event. %s" (:topic ballgown2exp-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for ballgown2exp events."
  []
  (events/start-event-listener! ballgown2exp-topics ballgown2exp-channel process-ballgown2exp-event!))
