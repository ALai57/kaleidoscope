(ns andrewslai.cljs.events
  (:require
   [andrewslai.cljs.db :refer [default-db]]
   [re-frame.core :refer [reg-event-db
                          reg-event-fx
                          inject-cofx
                          path
                          after
                          dispatch]]
   [ajax.core :refer [GET]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-recent-articles-url [] "/get-recent-articles")
(defn make-article-url [article-type article-name]
  (str "/get-article/" (name article-type) "/" (name article-name)))

(defn modify-db [db mods]
  (reduce-kv #(assoc %1 %2 %3) db mods))

;; Dispatched when setting the active panel


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

   (println "Retrieve-article path:"
            (make-article-url article-type article-name))

   (GET (make-article-url article-type article-name)
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

   (println "Retrieve resume-info")
   (println db)

   (GET "/get-resume-info"
       {:handler #(dispatch [:process-resume-info %1])
        :error-handler #(dispatch [:bad-resume-info %1])})

   (modify-db db {:loading-resume? true
                  :resume-info nil})))

(reg-event-db
 :process-resume-info
 (fn [db [_ response]]
   (println db)
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

   (GET (make-recent-articles-url)
       {:handler #(dispatch [:process-recent-response %1])
        :error-handler #(dispatch [:bad-recent-response %1])})

   (modify-db db {:loading? true
                  :active-panel value
                  :active-content nil
                  :recent-content nil})))

(reg-event-db
 :process-recent-response
 (fn [db [_ response]]
   ;;(println "SUCCESS Retreived recent articles: " response)
   (modify-db db {:loading? false
                  :recent-content response})))

(reg-event-db
 :bad-recent-response
 (fn [db [_ response]]
   (modify-db db {:loading? false
                  :recent-content "Unable to load content"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for radial menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(reg-event-db
 :toggle-menu
 (fn [db [_ _]]
   (let [new-value (not (:radial-menu-open? db))]
     (assoc db :radial-menu-open? new-value))))

(reg-event-db
 :click-radial-icon
 (fn [db [_ value]]
   (let [resume-info (:resume-info db)
         db-mod (if (= (first value) :me)
                  (assoc db :selected-resume-info resume-info)
                  db)]
     (assoc db-mod :active-icon value))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for d3 example
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(reg-event-db
 :update-circles
 (fn
   [db [_ idx param val]]
   #_(println "idx " idx "param " param "val " val)
   (assoc-in db [:circles idx param ] val)))

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


         associated-skills-names (map get-skill-name
                                      (flatten (map :skills_names associated-projects)))
         skills-filter (fn [s]
                         (some (fn [skill-name] (= skill-name (:name s)))
                               associated-skills-names))

         associated-skills (filter skills-filter all-skills)
         ]

     (println "associated-projects: " associated-projects)
     (println "associated-orgs: " associated-orgs)
     (println "associated-skills: " associated-skills)

     (modify-db db {:selected-resume-info {:organizations associated-orgs
                                           :projects associated-projects
                                           :skills associated-skills}
                    :selected-resume-category click-type
                    :selected-resume-card clicked-item-name}))))
