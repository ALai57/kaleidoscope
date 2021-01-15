(ns andrewslai.clj.entities.portfolio
  (:require [andrewslai.clj.persistence.postgres2 :as pg]
            [clojure.spec.alpha :as s]))

(s/def :andrewslai.portfolio/id int?)
(s/def :andrewslai.portfolio/name string?)
(s/def :andrewslai.portfolio/url string?)
(s/def :andrewslai.portfolio/image_url string?)
(s/def :andrewslai.portfolio/description string?)

(s/def :andrewslai.portfolio/organization
  (s/keys :req-un [:andrewslai.portfolio/id
                   :andrewslai.portfolio/name
                   :andrewslai.portfolio/url
                   :andrewslai.portfolio/image_url
                   :andrewslai.portfolio/description]))

(s/def :andrewslai.portfolio/organizations
  (s/coll-of :andrewslai.portfolio/organization))

(s/def :andrewslai.portfolio/organization_names
  (s/coll-of :andrewslai.portfolio/name))

(s/def :andrewslai.portfolio/skills-names
  (s/coll-of :andrewslai.portfolio/name))

(s/def :andrewslai.portfolio/project
  (s/keys :req-un [:andrewslai.portfolio/id
                   :andrewslai.portfolio/name
                   :andrewslai.portfolio/url
                   :andrewslai.portfolio/image_url
                   :andrewslai.portfolio/description
                   :andrewslai.portfolio/organization_names
                   :andrewslai.portfolio/skills_names]))

(s/def :andrewslai.portfolio/projects (s/coll-of :andrewslai.portfolio/project))

(s/def :andrewslai.portfolio/skill_category string?)

(s/def :andrewslai.portfolio/skill
  (s/keys :req-un [:andrewslai.portfolio/id
                   :andrewslai.portfolio/name
                   :andrewslai.portfolio/url
                   :andrewslai.portfolio/image_url
                   :andrewslai.portfolio/description
                   :andrewslai.portfolio/skill_category]))

(s/def :andrewslai.portfolio/skills
  (s/coll-of :andrewslai.portfolio/skill))

(s/def :andrewslai.portfolio/portfolio
  (s/keys :req-un [:andrewslai.portfolio/organizations
                   :andrewslai.portfolio/projects
                   :andrewslai.portfolio/skills]))

(defn get-portfolio [database]
  (let [orgs     (pg/select database
                            {:select [:*] :from [:organizations]})
        projects (pg/select database
                            {:select [:*] :from [:projects]})
        skills   (pg/select database
                            {:select [:*] :from [:skills]})]
    {:organizations orgs
     :projects      projects
     :skills        skills}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions to test DB connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (-> pg-db
      ->ProjectPortfolioDatabase
      get-project-portfolio
      :projects
      clojure.pprint/pprint)

  (-> pg-db
      ->ProjectPortfolioDatabase
      get-project-portfolio
      :organizations
      first
      clojure.pprint/pprint)

  (sql/query pg-db ["SELECT name FROM projects "])

  )
