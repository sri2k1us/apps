(ns apps.service.apps.jobs.sharing
  (:use [clojure-commons.core :only [remove-nil-values]]
        [clostache.parser :only [render]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.clients.data-info :as data-info]
            [apps.clients.iplant-groups :as ipg]
            [apps.clients.permissions :as perms-client]
            [apps.clients.notifications :as cn]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.jobs.params :as job-params]
            [apps.service.apps.jobs.permissions :as job-permissions]
            [apps.util.service :as service]
            [clojure.string :as string]
            [clojure-commons.error-codes :as ce]))

(defn- get-job-name
  [job-id {job-name :job_name}]
  (or job-name (str "analysis ID " job-id)))

(def job-sharing-formats
  {:not-found     "analysis ID {{analysis-id}} does not exist"
   :load-failure  "unable to load permissions for {{analysis-id}}: {{detail}}"
   :not-allowed   "insufficient privileges for analysis ID {{analysis-id}}"
   :is-subjob     "analysis sharing not supported for individual jobs within an HT batch"
   :not-supported "analysis sharing is not supported for jobs of this type"
   :is-group      "sharing an analysis with a group is not supported at this time"})

(defn- job-sharing-success
  [job-id job level output-share-err-msg app-share-err-msg]
  (remove-nil-values
    {:analysis_id   job-id
     :analysis_name (get-job-name job-id job)
     :permission    level
     :outputs_error output-share-err-msg
     :app_error     app-share-err-msg
     :success       true}))

(defn- job-sharing-failure
  [job-id job level reason]
  {:analysis_id   job-id
   :analysis_name (get-job-name job-id job)
   :permission    level
   :success       false
   :error         {:error_code ce/ERR_BAD_REQUEST
                   :reason     reason}})

(defn- job-unsharing-success
  [job-id job output-unshare-err-msg]
  (remove-nil-values
    {:analysis_id   job-id
     :analysis_name (get-job-name job-id job)
     :outputs_error output-unshare-err-msg
     :success       true}))

(defn- job-unsharing-failure
  [job-id job reason]
  {:analysis_id   job-id
   :analysis_name (get-job-name job-id job)
   :success       false
   :error         {:error_code ce/ERR_BAD_REQUEST
                   :reason     reason}})

(defn- job-sharing-msg
  ([reason-code job-id]
   (job-sharing-msg reason-code job-id nil))
  ([reason-code job-id detail]
   (render (job-sharing-formats reason-code)
           {:analysis-id job-id
            :detail (or detail "unexpected error")})))

(defn- has-analysis-permission
  [user job-id required-level]
  (seq (perms-client/load-analysis-permissions user [job-id] required-level)))

(defn- verify-accessible
  [sharer job-id]
  (when-not (has-analysis-permission (:shortUsername sharer) job-id "own")
    (job-sharing-msg :not-allowed job-id)))

(defn- verify-not-subjob
  [{:keys [id parent_id]}]
  (when parent_id
    (job-sharing-msg :is-subjob id)))

(defn- verify-support
  [apps-client job-id]
  (when-not (job-permissions/job-supports-job-sharing? apps-client job-id)
    (job-sharing-msg :not-supported job-id)))

(defn- verify-not-group
  [{subject-source-id :source_id subject-id :id} job-id]
  (when-not (ipg/user-source? subject-source-id)
    (job-sharing-msg :is-group job-id (str subject-id " is a group"))))

(defn- share-app-for-job
  [apps-client sharer sharee job-id {system-id :system_id app-id :app_id}]
  (when-not (.hasAppPermission apps-client sharee system-id app-id "read")
    (let [response (.shareAppWithSubject apps-client {} sharee system-id app-id "read")]
      (when-not (:success response)
        (get-in response [:error :reason] "unable to share app")))))

(defn- share-output-folder
  [sharer {sharee :id} {:keys [result_folder_path]}]
  (try+
   (data-info/share-path sharer result_folder_path sharee "read")
   nil
   (catch ce/clj-http-error? {:keys [body]}
     (str "unable to share result folder: " (:error_code (service/parse-json body))))))

(defn- share-input-file
  [sharer {sharee :id} path]
  (try+
   (data-info/share-path sharer path sharee "read")
   nil
   (catch ce/clj-http-error? {:keys [body]}
     (str "unable to share input file, " path ": " (:error_code (service/parse-json body))))))

(defn- process-child-jobs
  [f job-id]
  (first (remove nil? (map f (jp/list-child-jobs job-id)))))

(defn- list-job-inputs
  [apps-client {system-id :system_id app-id :app_id :as job}]
  (->> (mapv keyword (.getAppInputIds apps-client system-id app-id))
       (select-keys (job-params/get-job-config job))
       vals
       flatten
       (remove string/blank?)))

(defn- process-job-inputs
  [f apps-client job]
  (first (remove nil? (map f (list-job-inputs apps-client job)))))

(defn- share-child-job
  [apps-client sharer sharee level job]
  (or (process-job-inputs (partial share-input-file sharer sharee) apps-client job)
      (perms-client/share-analysis (:id job) sharee level)))

(defn- share-job*
  [apps-client sharer sharee job-id job level]
  (or (verify-not-subjob job)
      (verify-accessible sharer job-id)
      (verify-support apps-client job-id)
      (verify-not-group sharee job-id)
      (perms-client/share-analysis job-id sharee level)
      (process-job-inputs (partial share-input-file sharer sharee) apps-client job)
      (process-child-jobs (partial share-child-job apps-client sharer sharee level) job-id)))

(defn- share-job
  [apps-client sharer sharee {job-id :analysis_id level :permission}]
  (if-let [job (jp/get-job-by-id job-id)]
    (try+
     (if-let [failure-reason (share-job* apps-client sharer sharee job-id job level)]
       (job-sharing-failure job-id job level failure-reason)
       (job-sharing-success job-id job level
                            (share-output-folder sharer sharee job)
                            (share-app-for-job apps-client sharer sharee job-id job)))
     (catch [:type ::permission-load-failure] {:keys [reason]}
       (job-sharing-failure job-id job level (job-sharing-msg :load-failure job-id reason))))
    (job-sharing-failure job-id nil level (job-sharing-msg :not-found job-id))))

(defn- share-jobs-with-user
  [apps-client sharer {sharee :subject :keys [analyses]}]
  (let [responses (mapv (partial share-job apps-client sharer sharee) analyses)]
    (cn/send-analysis-sharing-notifications (:shortUsername sharer) sharee responses)
    {:subject  sharee
     :analyses responses}))

(defn share-jobs
  [apps-client user sharing-requests]
  (mapv (partial share-jobs-with-user apps-client user) sharing-requests))

(defn- unshare-output-folder
  [sharer {sharee :id} {:keys [result_folder_path]}]
  (try+
   (data-info/unshare-path sharer result_folder_path sharee)
   nil
   (catch ce/clj-http-error? {:keys [body]}
     (str "unable to unshare result folder: " (:error_code (service/parse-json body))))))

(defn- unshare-input-file
  [sharer {sharee :id} path]
  (try+
   (data-info/unshare-path sharer path sharee)
   nil
   (catch ce/clj-http-error? {:keys [body]}
     (str "unable to unshare input file: " (:error_code (service/parse-json body))))))

(defn- unshare-analysis
  [job-id sharee]
  (perms-client/unshare-analysis job-id sharee))

(defn- unshare-child-job
  [apps-client sharer sharee job]
  (or (process-job-inputs (partial unshare-input-file sharer sharee) apps-client job)
      (perms-client/unshare-analysis (:id job) sharee)))

(defn- unshare-job*
  [apps-client sharer sharee job-id job]
  (or (verify-not-subjob job)
      (verify-accessible sharer job-id)
      (verify-support apps-client job-id)
      (verify-not-group sharee job-id)
      (process-job-inputs (partial unshare-input-file sharer sharee) apps-client job)
      (perms-client/unshare-analysis job-id sharee)
      (process-child-jobs (partial unshare-child-job apps-client sharer sharee) job-id)))

(defn- unshare-job
  [apps-client sharer sharee job-id]
  (if-let [job (jp/get-job-by-id job-id)]
    (try+
     (if-let [failure-reason (unshare-job* apps-client sharer sharee job-id job)]
       (job-unsharing-failure job-id job failure-reason)
       (job-unsharing-success job-id job (unshare-output-folder sharer sharee job)))
     (catch [:type ::permission-load-failure] {:keys [reason]}
       (job-unsharing-failure job-id job (job-sharing-msg :load-failure job-id reason))))
    (job-unsharing-failure job-id nil (job-sharing-msg :not-found job-id))))

(defn- unshare-jobs-with-subject
  [apps-client sharer {sharee :subject :keys [analyses]}]
  (let [responses (mapv (partial unshare-job apps-client sharer sharee) analyses)]
    (cn/send-analysis-unsharing-notifications (:shortUsername sharer) sharee responses)
    {:subject  sharee
     :analyses responses}))

(defn unshare-jobs
  [apps-client user unsharing-requests]
  (mapv (partial unshare-jobs-with-subject apps-client user) unsharing-requests))
