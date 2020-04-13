(ns andrewslai.cljs.events
  (:require [andrewslai.cljs.db :refer [default-db]]
            [re-frame.core :refer [reg-event-db
                                   dispatch]]
            [ajax.core :refer [GET POST PATCH]]
            [ajax.json :as json]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-get-all-articles-url [] "/articles")
(defn make-article-url [article-name]
  (str "/articles/" (name article-name)))

(defn modify-db [db mods]
  (reduce-kv #(assoc %1 %2 %3) db mods))

(reg-event-db
  :initialize-db
  (fn [_ _]
    default-db))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for get-article
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(reg-event-db
  :retrieve-content
  (fn [db [_ article-type article-name]]

    (println "Retrieving article:"
             (make-article-url article-name))

    (GET (make-article-url article-name)
        {:handler #(dispatch [:process-response %1])
         :error-handler #(dispatch [:bad-response %1])})

    (modify-db db {:loading? true
                   :active-panel article-type
                   :active-content nil})))

(reg-event-db
 :process-response
 (fn [db [_ response]]
   (modify-db db {:loading? false
                  :active-content response})))

(reg-event-db
 :bad-response
 (fn [db [_ response]]
   (modify-db db {:loading? false
                  :active-content "Unable to load content"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for resume-info
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(reg-event-db
  :retrieve-resume-info
  (fn [db [_]]

    (GET "/get-resume-info"
        {:handler #(dispatch [:process-resume-info %1])
         :error-handler #(dispatch [:bad-resume-info %1])})

    (modify-db db {:loading-resume? true
                   :resume-info nil})))

(reg-event-db
  :process-resume-info
  (fn [db [_ response]]
    (modify-db db {:loading-resume? false
                   :resume-info response
                   :selected-resume-info response})))

(reg-event-db
  :bad-resume-info  
  (fn [db [_ response]]
    (modify-db db {:resume-info "Unable to load content"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for get-recent-articles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(reg-event-db
 :set-active-panel
 (fn [db [_ value]]

   (GET (make-get-all-articles-url)
        {:handler #(dispatch [:process-recent-response %1])
         :error-handler #(dispatch [:bad-recent-response %1])})

   (modify-db db {:loading? true
                  :active-panel value
                  :active-content nil
                  :recent-content nil})))

(reg-event-db
  :process-recent-response
  (fn [db [_ response]]
    (modify-db db {:loading? false
                   :recent-content response})))

(reg-event-db
  :bad-recent-response
  (fn [db [_ response]]
    (modify-db db {:loading? false
                   :recent-content "Unable to load content"})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for clicking on resume info
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-skill-name [y]
  (-> (.parse js/JSON y)
      js->clj
      keys
      first))

(defn find-associated-projects [clicked-item-name click-type all-projects]
  (let [contains-clicked-item? (fn [v] (= clicked-item-name v))

        skill-filter (fn [p]
                       (some contains-clicked-item?
                             (map get-skill-name (:skills_names p))))
        orgs-filter (fn [p] (some contains-clicked-item?
                                  (:organization_names p)))
        project-filter (fn [p] (some contains-clicked-item? [(:name p)]))

        project (case click-type
                  :project (filter project-filter all-projects)
                  :organization (filter orgs-filter all-projects)
                  :skill (filter skill-filter all-projects)
                  nil)]
    project))

(reg-event-db
  :click-resume-info-card
  (fn [db [_ click-type clicked-item-name]]
    (let [{all-projects :projects
           all-orgs :organizations
           all-skills :skills} (:resume-info db)

          associated-projects (find-associated-projects clicked-item-name
                                                        click-type
                                                        all-projects)

          associated-org-names (flatten (map :organization_names
                                             associated-projects))

          orgs-filter (fn [o]
                        (some (fn [org-name] (= org-name (:name o)))
                              associated-org-names))

          associated-orgs (filter orgs-filter all-orgs)


          associated-skills-names
          (map get-skill-name
               (flatten (map :skills_names associated-projects)))

          skills-filter (fn [s]
                          (some (fn [skill-name] (= skill-name (:name s)))
                                associated-skills-names))

          associated-skills (filter skills-filter all-skills)]

      #_(println "associated-projects: " associated-projects)
      #_(println "associated-orgs: " associated-orgs)
      #_(println "associated-skills: " associated-skills)

      (modify-db db {:selected-resume-info {:organizations associated-orgs
                                            :projects associated-projects
                                            :skills associated-skills}
                     :selected-resume-category click-type
                     :selected-resume-card clicked-item-name}))))

(reg-event-db
 :reset-resume-info
 (fn [db [_ _]]
   (assoc db :selected-resume-info (:resume-info db))))


(reg-event-db
  :test-transitions
  (fn [db [_ value]]
    (assoc db :test-transitions value)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for logging in
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn image->blob [the-bytes]
  (let [data-blob (js/Blob. #js [the-bytes] #js {:type "image/png"})]
    (.createObjectURL js/URL data-blob)))

(defn update-user-profile [db [_ {:keys [avatar] :as user}]]
  (if (empty? user)
    (assoc db :user nil)
    (let [updated-user (assoc user :avatar (image->blob avatar))]
      (assoc db :user updated-user))))

;; TODO: Revoke URLs when logged out!
(reg-event-db
  :process-login-response
  update-user-profile)

(reg-event-db
  :change-password
  (fn [db [_ password]]
    (assoc db :password password)))

(reg-event-db
  :change-username
  (fn [db [_ username]]
    (assoc db :username username)))

(reg-event-db
  :login-click
  (fn [{:keys [username password] :as db}]
    (POST "/login"
        {:params {:username username :password password}
         :format :json
         :handler #(dispatch [:process-login-response %1])
         :error-handler #(dispatch [:bad-recent-response %1])})
    db))

(reg-event-db
  :process-logout-response
  (fn [db & args]
    (assoc db :user nil)))

(reg-event-db
  :logout
  (fn [{:keys [username password] :as db}]
    (POST "/logout"
        {:handler #(dispatch [:process-logout-response %1])
         :error-handler #(dispatch [:bad-recent-response %1])})
    db))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for updating profile
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Right now, we don't have a good way to know if the update was successful
;;       or not. Need to make the update endpoint send different status codes
;;       depending on whether the update was successful or not.
;;       Also need to update this event handler to handle both cases

;; TODO: Support avatar uploads in this function too. Right now it will be
;;       unhappy/unable to support conversion of an avatar image into a blob
(reg-event-db
  :process-update-profile
  (fn [db [_ user]]
    (if (empty? user)
      (assoc db :user nil)
      (assoc db :user (merge (:user db) user)))))

(reg-event-db
  :update-profile
  (fn [db [_ {:keys [username] :as request}]]

    (PATCH (str "/users/" username)
           {:params request
            :format :json
            :handler #(dispatch [:process-update-profile %])
            :error-handler #(dispatch [:bad-recent-response %])})

    db))
