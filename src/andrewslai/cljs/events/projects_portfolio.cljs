(ns andrewslai.cljs.events.projects-portfolio
  (:require [ajax.core :refer [GET]]
            [re-frame.core :refer [dispatch reg-event-db]]))

(defn json-string->clj [s]
  (-> js/JSON
      (.parse s)
      js->clj))

(defn parse-project-skills [p]
  (let [skills (:skills_names p)
        parsed-skills (map json-string->clj skills)]
    (assoc p :skills_names parsed-skills)))

(defn load-portfolio-cards [db [_ {:keys [projects] :as response}]]
  (let [parsed-projects (map parse-project-skills projects)
        updated-resume-info (assoc response :projects parsed-projects)]
    (merge db {:loading-resume? false
               :resume-info updated-resume-info
               :selected-resume-info updated-resume-info})))
(reg-event-db
 :load-portfolio-cards
 load-portfolio-cards)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for clicking on resume info
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti find-projects-associated-with-card
  (fn [{:keys [category name]} _] category))


(defn find-associated-projects [{:keys [name category]} all-projects]
  (let [contains-clicked-item? (fn [v] (= name v))

        skill-filter
        (fn [p]
          (some contains-clicked-item? (map (comp first keys)
                                            (:skills_names p))))

        orgs-filter
        (fn [p] (some contains-clicked-item? (:organization_names p)))

        project-filter
        (fn [p] (some contains-clicked-item? [(:name p)]))

        project (case category
                  :project (filter project-filter all-projects)
                  :organization (filter orgs-filter all-projects)
                  :skill (filter skill-filter all-projects)
                  nil)]
    project))

(defn select-portfolio-card
  [{:keys [resume-info] :as db} [_ {:keys [category name] :as card}]]
  (let [{:keys [projects organizations skills]} resume-info

        associated-projects
        (find-associated-projects card projects)

        associated-org-names
        (flatten (map :organization_names associated-projects))

        orgs-filter (fn [o]
                      (some (fn [org-name] (= org-name (:name o)))
                            associated-org-names))

        associated-orgs (filter orgs-filter organizations)


        associated-skills-names
        (map (comp first keys)
             (flatten (map :skills_names associated-projects)))

        skills-filter (fn [s]
                        (some (fn [skill-name] (= skill-name (:name s)))
                              associated-skills-names))

        associated-skills (filter skills-filter skills)]

    (merge db {:selected-resume-info {:organizations associated-orgs
                                      :projects associated-projects
                                      :skills associated-skills}
               :selected-resume-category category
               :selected-resume-card name})))

(reg-event-db
 :select-portfolio-card
 select-portfolio-card)

(reg-event-db
 :reset-portfolio-cards
 (fn [db [_ _]]
   (assoc db :selected-resume-info (:resume-info db))))

(reg-event-db
 :test-transitions
 (fn [db [_ value]]
   (assoc db :test-transitions value)))

(comment
  #_(fn [{:keys [resume-info] :as db} [_ category clicked-item]]
      (let [{:keys [projects organizations skills]} resume-info

            associated-projects
            (find-associated-projects clicked-item category projects)

            associated-org-names
            (flatten (map :organization_names associated-projects))

            orgs-filter (fn [o]
                          (some (fn [org-name] (= org-name (:name o)))
                                associated-org-names))

            associated-orgs (filter orgs-filter organizations)


            associated-skills-names
            (map get-skill-name
                 (flatten (map :skills_names associated-projects)))

            skills-filter (fn [s]
                            (some (fn [skill-name] (= skill-name (:name s)))
                                  associated-skills-names))

            associated-skills (filter skills-filter skills)]

        #_(println "associated-projects: " associated-projects)
        #_(println "associated-orgs: " associated-orgs)
        #_(println "associated-skills: " associated-skills)

        (merge db {:selected-resume-info {:organizations associated-orgs
                                          :projects associated-projects
                                          :skills associated-skills}
                   :selected-resume-category category
                   :selected-resume-card clicked-item}))))
