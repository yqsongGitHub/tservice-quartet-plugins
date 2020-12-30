(ns plugins.tools.sigma
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [spec-tools.core :as st]
            [clojure.string :as str]
            [spec-tools.json-schema :as json-schema]
            [tservice.config :refer [get-workdir]]
            [tservice.events :as events]
            [plugins.wrappers.sigma :as sigma-lib]
            [tservice.lib.fs :as fs-lib]
            [tservice.util :as u]))

;;; ------------------------------------------------ Event Specs ------------------------------------------------
(s/def ::filetype
  (st/spec
   {:spec                #(#{"maf" "vcf"} %)
    :type                :string
    :description         "File Type (maf or vcf)"
    :swagger/default     "vcf"
    :reason              "Only support maf or vcf"}))

(s/def ::tumor_type
  (st/spec
   {:spec                #(#{"bladder" "bone_other" "breast" "crc" "eso" "gbm" "lung" "lymph" "medullo" "osteo" "ovary" "panc_ad" "panc_en" "prost" "stomach" "thy" "uterus"} %)
    :type                :string
    :description         "The options are 'bladder', 'bone_other' (Ewing's sarcoma or Chordoma), 'breast', 'crc','eso', 'gbm', 'lung', 'lymph', 'medullo', 'osteo', 'ovary', 'panc_ad', 'panc_en', 'prost', 'stomach', 'thy', or 'uterus'."
    :swagger/default     "breast"
    :reason              "Only support bladder, bone_other, breast, crc, eso, gbm, lung, lymph, medullo, osteo, ovary, panc_ad, panc_en, prost, stomach, thy or uterus"}))

(s/def ::data
  (st/spec
   {:spec                #(#{"msk" "seqcap" "seqcap_probe" "wgs"} %)
    :type                :string
    :description         "The options are 'msk' (for a panel that is similar size to MSK-Impact panel with 410 genes), 'seqcap' (for whole exome sequencing), 'seqcap_probe' (64 Mb SeqCap EZ Probe v3), or 'wgs' (for whole genome sequencing)"
    :swagger/default     "seqcap"
    :reason              "Only support msk, seqcap, seqcap_propbe or wgs"}))

(s/def ::do_assign
  (st/spec
   {:spec                boolean?
    :type                :bool
    :description         "A boolean for whether a cutoff should be applied to determine the final decision or just the features should be returned"
    :swagger/default     true
    :reason              "Only support true or false"}))

(s/def ::do_mva
  (st/spec
   {:spec                boolean?
    :type                :bool
    :description         "A boolean for whether multivariate analysis should be run"
    :swagger/default     true
    :reason              "Only support true or false"}))

(s/def ::lite_format
  (st/spec
   {:spec                boolean?
    :type                :bool
    :description         "Saves the output in a lite format when set to true"
    :swagger/default     true
    :reason              "Only support true or false"}))

(s/def ::check_msi
  (st/spec
   {:spec                boolean?
    :type                :bool
    :description         "A boolean which determines whether the user wants to identify micro-sattelite instable tumors"
    :swagger/default     true
    :reason              "Only support true or false"}))

(s/def ::filepath
  (st/spec
   {:spec                (s/and string? #(re-matches #"^[a-zA-Z0-9]+:\/\/(\/|\.\/)[a-zA-Z0-9_]+.*" %))
    :type                :string
    :description         "File path for covertor."
    :swagger/default     nil
    :reason              "The filepath must be string."}))

(s/def ::parameters
  (s/keys :req-un []
          :opt-un [::filetype ::tumor_type ::data ::do_assign ::do_mva ::lite_format ::check_msi]))

(def sigma-params-body
  "A spec for the body parameters."
  (s/keys :req-un [::filepath ::parameters]))

;;; ------------------------------------------------ Event Metadata -------------------------------------------------
(def metadata
  {:route ["/tool/sigma"
           {:tags ["Tool"]
            :post {:summary "Mutational signature analysis for low statistics SNV data."
                   :parameters {:body sigma-params-body}
                   :responses {201 {:body {:output_file string? :log_url string?}}}
                   :handler (fn [{{{:keys [filepath parameters]} :body} :parameters}]
                              (let [workdir (get-workdir)
                                    from-path (if (re-matches #"^file:\/\/\/.*" filepath)
                                                ; Absolute path with file://
                                                (str/replace filepath #"^file:\/\/" "")
                                                (fs-lib/join-paths workdir (str/replace filepath #"^file:\/\/" "")))
                                    uuid (u/uuid)
                                    relative-dir (fs-lib/join-paths "download" uuid)
                                    output-file (fs-lib/join-paths relative-dir "sigma-results.csv")
                                    log-path (fs-lib/join-paths relative-dir "log")
                                    to-dir (fs-lib/join-paths workdir relative-dir)]
                                (fs-lib/create-directories! to-dir)
                                ; Launch the sigma
                                (spit (fs-lib/join-paths workdir log-path) (json/write-str {:status "Running" :msg ""}))
                                (events/publish-event! :sigma-convert
                                                       {:input-file from-path
                                                        :parameters parameters
                                                        :dest-dir to-dir})
                                {:status 201
                                 :body {:output_file output-file
                                        :log_url log-path}}))}
            :get {:summary "A json shema for sigma."
                  :parameters {}
                  :responses {200 {:body map?}}
                  :handler (fn [_]
                             {:status 200
                              :body (json-schema/transform sigma-params-body)})}}]
   :manifest {:description "Mutational signature analysis for low statistics SNV data."
              :category "Tool"
              :home "https://github.com/clinico-omics/tservice-plugins"
              :name "Mutational Signature Analysis"
              :source "PGx"
              :short_name "sigma"
              :icons [{:src "", :type "image/png", :sizes "192x192"}
                      {:src "", :type "image/png", :sizes "192x192"}]
              :author "Parklab"
              :hidden false
              :id "98b3aa7d24b6bdd4a4eeef5c9a117164"
              :app_name "parklab/sigma"}})

(def ^:const sigma-topics
  "The `Set` of event topics which are subscribed to for use in sigma tracking."
  #{:sigma-convert})

(def ^:private sigma-channel
  "Channel for receiving event sigma we want to subscribe to for sigma events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- sigma!
  [input-file dest-dir parameters]
  (let [log-path (fs-lib/join-paths dest-dir "log")
        output-file (fs-lib/join-paths dest-dir "sigma-results.csv")]
    (try
      (log/info (format "Mutational signature analysis for low statistics SNV data: [%s %s %s]" input-file parameters output-file))
      (let [sigma-result (sigma-lib/call-sigma! input-file output-file parameters)
            log (json/write-str sigma-result)]
        (log/info "Status: " sigma-result)
        (spit log-path log))
      (catch Exception e
        (let [log (json/write-str {:status "Error" :msg (.toString e)})]
          (log/info "Status: " log)
          (spit log-path log))))))

(defn- process-sigma-event!
  "Handle processing for a single event notification received on the sigma-channel"
  [sigma-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} sigma-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "sigma" (sigma! (:input-file object) (:dest-dir object) (:parameters object))))
    (catch Throwable e
      (log/warn (format "Failed to process sigma event. %s" (:topic sigma-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for sigma events."
  []
  (events/start-event-listener! sigma-topics sigma-channel process-sigma-event!))
