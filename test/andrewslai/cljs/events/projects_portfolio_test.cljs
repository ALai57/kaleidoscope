(ns andrewslai.cljs.events.projects-portfolio-test
  (:require [andrewslai.cljs.events.projects-portfolio :as pp]
            [cljs.test :refer-macros [deftest is testing]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parsing json-string to cljs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def example-skill "{\"Heap\": \"Analytics\"}")
(def parsed-skill {"Heap" "Analytics"})

(deftest parse-skills-test
  (testing "Parse JSON string into clojurescript"
    (is (= parsed-skill (pp/json-string->clj example-skill)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parsing project skills
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def example-project
  {:id 1,
   :name "The experiment that shocked the world",
   :url "https://helix.northwestern.edu/article/experiment-shocked-world",
   :image_url "images/nu-helix-logo.svg",
   :description "A short article on Luigi Galvani",
   :organization_names ["HELIX"],
   :skills_names [example-skill]})
(def parsed-project
  (assoc example-project :skills_names [parsed-skill]))

(deftest parse-project-skills
  (testing "Parse project skill"
    (is (= parsed-project (pp/parse-project-skills example-project)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Load response into DB
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def example-response
  {:organizations
   '({:id 1,
      :name "HELIX",
      :url "https://helix.northwestern.edu",
      :image_url "images/nu-helix-logo.svg",
      :description "Northwestern science outreach magazine"})

   :projects
   (list example-project),

   :skills
   '({:id 1,
      :name "Periscope Data",
      :url nil,
      :image_url "images/periscope-logo.svg",
      :description "Analytics tool",
      :skill_category "Analytics Tools"})})

(def expected-resume-info
  (assoc example-response :projects (list parsed-project)))

(def expected-db-state
  {:loading-resume? false
   :selected-resume-info expected-resume-info
   :resume-info expected-resume-info})

(deftest loading-portfolio-cards
  (testing "Portfolio cards are inserted into DB correctly"
    (is (= expected-db-state
           (pp/load-portfolio-cards {} [nil example-response])))))
