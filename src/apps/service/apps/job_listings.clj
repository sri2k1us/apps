(ns apps.service.apps.job-listings
  (:use [kameleon.uuids :only [uuidify]]
        [apps.util.conversions :only [remove-nil-vals]])
  (:require [apps.clients.permissions :as perms-client]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.jobs.permissions :as job-permissions]
            [apps.service.apps.util :as apps-util]
            [apps.service.util :as util]
            [clojure.string :as string]
            [kameleon.db :as db]))

(defn- job-timestamp
  [timestamp]
  (str (or (db/millis-from-timestamp timestamp) 0)))

(defn- app-disabled?
  [app-tables system-id app-id]
  (let [qualified-id  (apps-util/qualified-app-id system-id app-id)
        disabled-flag (:disabled (first (remove nil? (map #(% qualified-id) app-tables))))]
    (if (nil? disabled-flag) true disabled-flag)))

(defn- batch-child-status
  [{:keys [status]}]
  (cond (jp/completed? status) :completed
        (jp/running? status)   :running
        :else                  :submitted))

(def ^:private empty-batch-child-status
  {:total     0
   :completed 0
   :running   0
   :submitted 0})

(defn- format-batch-status
  [batch-id]
  (merge empty-batch-child-status
         (let [children (jp/list-child-jobs batch-id)]
           (assoc (->> (group-by batch-child-status children)
                       (map (fn [[k v]] [k (count v)]))
                       (into {}))
                  :total (count children)))))

(defn- job-supports-sharing?
  [apps-client perms rep-steps {:keys [parent_id id]}]
  (and (nil? parent_id)
       (job-permissions/job-steps-support-job-sharing? apps-client (rep-steps id))
       (= (get perms id) "own")))

(defn format-base-job
  [{:keys [parent_id id] :as job}]
  (remove-nil-vals
   {:app_description (:app_description job)
    :app_id          (:app_id job)
    :app_name        (:app_name job)
    :description     (:description job)
    :enddate         (job-timestamp (:end_date job))
    :system_id       (:system_id job)
    :id              id
    :name            (:job_name job)
    :resultfolderid  (:result_folder_path job)
    :startdate       (job-timestamp (:start_date job))
    :status          (:status job)
    :username        (:username job)
    :deleted         (:deleted job)
    :notify          (:notify job false)
    :wiki_url        (:app_wiki_url job)
    :parent_id       parent_id
    :batch           (:is_batch job)
    :batch_status    (when (:is_batch job) (format-batch-status id))}))

(defn format-admin-job
  [job]
  (remove-nil-vals
   (assoc (format-base-job job)
     :external_ids (vec (.getArray (:external_ids job))))))

(defn format-job
  [apps-client perms app-tables rep-steps job]
  (remove-nil-vals
   (assoc (format-base-job job)
     :app_disabled (app-disabled? app-tables (:system_id job) (:app_id job))
     :can_share    (job-supports-sharing? apps-client perms rep-steps job))))

(defn- list-jobs*
  [{:keys [username]} search-params types analysis-ids]
  (jp/list-jobs-of-types username search-params types analysis-ids))

(defn- count-jobs
  [{:keys [username]} {:keys [filter include-hidden]} types analysis-ids]
  (jp/count-jobs-of-types username filter include-hidden types analysis-ids))

(defn list-jobs
  [apps-client user {:keys [sort-field] :as params}]
  (let [perms            (perms-client/load-analysis-permissions (:shortUsername user))
        analysis-ids     (set (keys perms))
        default-sort-dir (if (nil? sort-field) :desc :asc)
        search-params    (util/default-search-params params :startdate default-sort-dir)
        types            (.getJobTypes apps-client)
        jobs             (list-jobs* user search-params types analysis-ids)
        rep-steps        (group-by (some-fn :parent_id :job_id) (jp/list-representative-job-steps (mapv :id jobs)))
        app-tables       (.loadAppTables apps-client jobs)]
    {:analyses  (mapv (partial format-job apps-client perms app-tables rep-steps) jobs)
     :timestamp (str (System/currentTimeMillis))
     :total     (count-jobs user params types analysis-ids)}))

(defn admin-list-jobs-with-external-ids [external-ids]
  {:analyses (mapv format-admin-job (jp/list-jobs-by-external-id external-ids))})

(defn list-job
  [apps-client job-id]
  (let [job-info   (jp/get-job-by-id job-id)
        app-tables (.loadAppTables apps-client [job-info])
        rep-steps  (group-by :job_id (jp/list-representative-job-steps [job-id]))]
    (format-job apps-client nil app-tables rep-steps job-info)))

(defn- format-job-step
  [step]
  (remove-nil-vals
   {:step_number     (:step_number step)
    :external_id     (:external_id step)
    :startdate       (job-timestamp (:start_date step))
    :enddate         (job-timestamp (:end_date step))
    :status          (:status step)
    :app_step_number (:app_step_number step)
    :step_type       (:job_type step)}))

(defn list-job-steps
  [job-id]
  (let [steps (jp/list-job-steps job-id)]
    {:analysis_id job-id
     :steps       (map format-job-step steps)
     :timestamp   (str (System/currentTimeMillis))
     :total       (count steps)}))
