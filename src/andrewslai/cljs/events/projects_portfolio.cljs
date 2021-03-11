(ns andrewslai.cljs.events.projects-portfolio
  (:require [ajax.core :as ajax]
            [re-frame.core :refer [dispatch reg-event-db reg-event-fx]]))

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

;; Because the point of this NS is to deal with projects, it's important to find
;; out which projects a given "click" was associated with. For example, if a
;; user clicks on the skill "Python", we first want to find out which projects
;; used that skill
(defmulti select-projects-associated-with-card
  (fn [{:keys [category name]} _] category))

(defmethod select-projects-associated-with-card :project
  [{card-name :name} projects]
  (filter (fn [project]
            (let [project-name (:name project)]
              (= card-name project-name)))
          projects))

(defmethod select-projects-associated-with-card :organization
  [{card-name :name} projects]
  (filter (fn [project]
            (let [organizations (:organization_names project)]
              (some (fn [organization] (= card-name organization))
                    organizations)))
          projects))

(defmethod select-projects-associated-with-card :skill
  [{card-name :name} projects]
  (filter (fn [project]
            (let [skills (map (comp first keys) (:skills_names project))]
              (some (fn [skill] (= card-name skill))
                    skills)))
          projects))

(defn select-organizations-associated-with-project [projects organizations]
  (println projects)
  (let [associated-organizations (->> projects
                                      (map :organization_names)
                                      flatten
                                      set)]
    (filter (fn [{organization-name :name}]
              (associated-organizations organization-name))
            organizations)))

;; Select skills associated with project

;; Once we've identified the project, we need to find all things associated with
;; that project
(defn select-portfolio-card
  [{:keys [resume-info] :as db} [_ {:keys [category name] :as card}]]
  (let [{:keys [projects organizations skills]} resume-info

        associated-projects
        (select-projects-associated-with-card card projects)

        associated-orgs
        (select-organizations-associated-with-project associated-projects
                                                      organizations)
        ;;(filter orgs-filter organizations)


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

(reg-event-fx
 :request-portfolio-cards
 (fn [{:keys [db]} [_ article-name]]
   {:http-xhrio {:method          :get
                 :uri             "/projects-portfolio"
                 :format          (ajax/json-response-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:load-portfolio-cards]
                 :on-failure      [:load-portfolio-cards]}
    :db         (assoc db :loading? true)}))
