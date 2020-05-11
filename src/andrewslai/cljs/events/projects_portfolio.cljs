(ns andrewslai.cljs.events.projects-portfolio
  (:require [ajax.core :refer [GET]]
            [andrewslai.cljs.events.core :refer [modify-db]]
            [re-frame.core :refer [dispatch reg-event-db]]))


(defn load-portfolio-cards [db [_ response]]
  (merge db {:loading-resume? false
             :resume-info response
             :selected-resume-info response}))
(reg-event-db
  :load-portfolio-cards
  load-portfolio-cards)

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
  :select-portfolio-card
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
  :reset-portfolio-cards
  (fn [db [_ _]]
    (assoc db :selected-resume-info (:resume-info db))))

(reg-event-db
  :test-transitions
  (fn [db [_ value]]
    (assoc db :test-transitions value)))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for resume-info
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
#_(defn process-resume-info [db response]
    (merge db {:loading-resume? false
               :resume-info response
               :selected-resume-info response}))

#_(defn bad-resume-info [db response]
    (merge db {:loading-resume? false
               :resume-info "Unable to load content"}))

#_(reg-event-db
    :retrieve-resume-info
    (fn [db [_]]

      (GET "/get-resume-info"
          {:handler #(dispatch [:process-http-response %1 process-resume-info])
           :error-handler #(dispatch [:process-http-response %1 bad-resume-info])})

      (modify-db db {:loading-resume? true
                     :resume-info nil})))
