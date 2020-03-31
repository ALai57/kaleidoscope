(ns andrewslai.clj.auth.mock
  (:require [buddy.hashers :as hashers]
            [ring.util.response :refer [response redirect]]))

(defn uuid [] (java.util.UUID/randomUUID))
(def userstore (atom {}))


(defn create-user! [user]
  (let [password (:password user)
        user-id (uuid)

        entry (-> user
                  (assoc :id user-id
                         :password-hash (hashers/encrypt password))
                  (dissoc :password))]
    (println password user-id entry)
    (swap! userstore assoc user-id entry)))

(defn get-user [user-id]
  (get @userstore user-id))


(defn get-user-by-username-and-password [username password]
  (prn username password)
  (reduce (fn [_ user]
            (if (and (= (:username user) username)
                     (hashers/check password (:password-hash user)))
              (reduced user)))
          (vals @userstore)))



(comment
  (create-user! {:username "Andrew"
                 :password "Lai"})

  (clojure.pprint/pprint @userstore)

  (get-user #uuid "7c20b056-945a-4389-9ef4-748b14461495")
  

  (get-user-by-username-and-password "Andrew" "Lai")
  )


(defn post-login [{{username "username" password "password"} :form-params
                   session :session :as req}]
  (if-let [user (get-user-by-username-and-password username password)]

    (assoc (redirect "/")
           :session (assoc session :identity (:id user)))

    (redirect "/login/")))

(defn post-logout [{session :session}]
  (assoc (redirect "/login/")
         :session (dissoc session :identity)))


(defn is-authenticated? [{user :user :as req}]
  (not (nil? user)))

(defn wrap-user [handler]
  (fn [{user-id :identity :as req}]
    (handler (assoc req :user (get-user user-id)))))
