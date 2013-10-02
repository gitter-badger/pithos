(ns io.pithos.operations
  (:require [io.pithos.response     :refer [header response status
                                            xml-response request-id
                                            content-type exception-status]]
            [io.pithos.store        :as store]
            [io.pithos.bucket       :as bucket]
            [io.pithos.object       :as object]
            [io.pithos.xml          :as xml]
            [clojure.tools.logging  :refer [debug info warn error]]))

(defn get-region
  [regions region]
  (or (get regions region)
      (throw (ex-info (str "could not find region: " region)
                      {:status-code 500}))))

(defn get-service
  "lists all bucket"
  [{{:keys [tenant]} :authorization :as request} metastore regions]
  (store/execute metastore
    (-> (bucket/by-tenant tenant)
        (xml/list-all-my-buckets)
        (xml-response)
        (request-id request))))

(defn put-bucket
  [{{:keys [tenant]} :authorization :keys [bucket] :as request}
   metastore regions]
  (store/execute metastore (bucket/create! tenant bucket {}))
  (-> (response)
      (request-id request)
      (header "Location" (str "/" bucket))
      (header "Connection" "close")))

(defn delete-bucket
  [{{:keys [tenant]} :authorization :keys [bucket] :as request}
   metastore regions]
  (debug "delete! called on bucket " tenant bucket)
  (store/execute metastore (bucket/delete! bucket))
  (-> (response)
      (request-id request)
      (status 204)))

(defn put-bucket-acl
  [{:keys [bucket body] :as request} metastore regions]
  (let [acl (slurp body)]
    (store/execute metastore (bucket/update! bucket {:acl acl}))
    (-> (response)
        (request-id request))))

(defn get-bucket-acl
  [{:keys [bucket] :as request} metastore regions]
  (store/execute metastore
                 (-> (bucket/fetch bucket)
                     :acl
                     (xml/default)
                     (xml-response)
                     (request-id request))))

(defn get-object
  [{:keys [bucket object] :as request} metastore regions]
  ;; get object !
  (-> (response "toto\n")
      (request-id request)
      (content-type "text/plain")))

(defn put-object
  [{:keys [bucket object] :as request} metastore regions]
  ;; put object !
  (-> (response)
      (request-id request)))


(defn head-object
  [{:keys [bucket object] :as request} metastore regions]
  (-> (response)
      (request-id request)))

(defn get-object-acl
  [{:keys [bucket object] :as request} metastore regions]
  (let [{:keys [region]} (store/execute metastore (bucket/fetch bucket))
        metastore       (get-region regions region)]
    (-> (store/execute metastore (object/fetch bucket object))
        :acl
        (xml/default)
        (xml-response)
        (request-id request))))

(defn put-object-acl
  [{:keys [bucket object body] :as request} metastore regions]
  (let [{:keys [region]} (store/execute metastore (bucket/fetch bucket))
        metastore        (get-region regions region)
        acl              (slurp body)]
    (store/execute metastore (object/update! bucket object {:acl acl}))
    (-> (response)
        (request-id request))))

(defn delete-object
  [{:keys [bucket object] :as request} metastore regions]
  (let [{:keys [region]} (store/execute metastore (bucket/fetch bucket))
        metastore        (get-region regions region)]
    ;; delete object
    (-> (response)
        (request-id request))))

(defn unknown
  "unknown operation"
  [req metastore regions]
  (-> (xml/unknown req)
      (xml-response)))

(def opmap
  {:get-service {:handler get-service 
                 :perms   [:authenticated]}
   :put-bucket  {:handler put-bucket 
                 :perms   [[:memberof "authenticated-users"]]}
   :delete-bucket {:handler delete-bucket 
                   :perms [[:memberof "authenticated-users"]
                           [:bucket   :owner]]}
   :get-bucket-acl {:handler get-bucket-acl
                    :perms   [[:bucket "READ_ACP"]]}
   :put-bucket-acl {:handler put-bucket-acl
                    :perms [[:bucket "WRITE_ACP"]]}
   :get-object {:handler head-object
                :perms [[:object "READ"]]}
   :head-object {:handler head-object
                 :perms [[:object "READ"]]}
   :put-object {:handler put-object
                :perms   [[:bucket "WRITE"]]}
   :delete-object {:handler delete-object
                   :perms [[:bucket "WRITE"]]}
   :get-object-acl {:handler get-object-acl 
                    :perms [[:object "READ_ACP"]]}
   :put-object-acl {:handler put-object-acl 
                    :perms [[:object "WRITE_ACP"]]}})

(defmacro ensure!
  [pred]
  `(when-not ~pred
     (debug "could not ensure: " (str (quote ~pred)))
     (throw (ex-info "access denied" {:status-code 403
                                      :type        :access-denied}))))

(defn granted?
  [acl needs for]
  (= (get acl for) needs))

(defn bucket-satisfies?
  [{:keys [tenant acl]} & {:keys [for groups needs]}]
  (or (= tenant for)
      (granted? acl needs for)
      (some identity (map (partial granted? acl needs) groups))))

(defn object-satisfies?
  [{tenant :tenant} {acl :acl} & {:keys [for groups needs]}]
  (or (= tenant for)
      (granted? acl needs for)
      (some identity (map (partial granted? acl needs) groups))))

(defn authorize
  [{:keys [authorization bucket object] :as request} perms metastore regions]
  (let [{:keys [tenant memberof]} authorization
        memberof?                 (set memberof)]
    (doseq [[perm arg] (map (comp flatten vector) perms)]
      (debug "about to validate " perm arg tenant memberof?)
      (case perm
        :authenticated (ensure! (not= tenant :anonymous))
        :memberof      (ensure! (memberof? arg))
        :bucket        (ensure! (bucket-satisfies? (store/execute metastore
                                                     (bucket/fetch bucket))
                                                   :for    tenant
                                                   :groups memberof?
                                                   :needs  arg))
        :object        (ensure! (object-satisfies? (store/execute metastore
                                                     (bucket/fetch bucket))
                                                   (object/fetch bucket object)
                                                   :for    tenant
                                                   :groups memberof?
                                                   :needs  arg))))))

(defn ex-handler
  [request exception]
  (-> (xml-response (xml/exception request exception))
      (exception-status (ex-data exception))
      (request-id request)))

(defn dispatch
  [{:keys [operation] :as request} metastore regions]
  (let [{:keys [handler perms] :or {handler unknown}} (get opmap operation)]
    (try (authorize request perms metastore regions)
         (handler request metastore regions)
         (catch Exception e
           (when-not (:type (ex-data e))
             (error e "caught exception during operation"))
           (ex-handler request e)))))


