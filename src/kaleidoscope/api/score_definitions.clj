(ns kaleidoscope.api.score-definitions
  (:require [kaleidoscope.persistence.projects :as persistence]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default score definitions seeded for every new user
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def intent-clarity-definition
  {:name        "Intent Clarity"
   :description "Evaluates how clearly the project's purpose, target users, and goals are articulated. A high score means anyone reading the description immediately understands what the project does, who it's for, and why it matters."
   :scorer-type "pm"
   :is-default  true
   :dimensions  [{:name     "Problem Clarity"
                  :criteria "Is the core problem or unmet need clearly defined? Can a reader articulate what specific frustration or gap this project addresses?"}
                 {:name     "User Behaviors"
                  :criteria "Are the user behaviors the project seeks to change or enable clearly described? Is it obvious what users will do differently because of this project?"}
                 {:name     "Success Metrics"
                  :criteria "Are there concrete, measurable indicators of success defined? Would you know when the project has achieved its goal?"}
                 {:name     "Market Context"
                  :criteria "Is the relevant market or audience described? Is the size and accessibility of the target audience clear?"}
                 {:name     "Differentiation"
                  :criteria "Does the description explain how this is meaningfully different from existing alternatives? Is the unique value proposition articulated?"}]})

(def architecture-clarity-definition
  {:name        "Architecture Clarity"
   :description "Evaluates how well the technical design, system components, and implementation approach are thought through. A high score means the engineering approach is sound, risks are identified, and the path to implementation is clear."
   :scorer-type "engineering_lead"
   :is-default  true
   :dimensions  [{:name     "Module Design"
                  :criteria "Are the system's major components or modules clearly defined with coherent responsibilities? Is there a sensible separation of concerns?"}
                 {:name     "API Design"
                  :criteria "Are the interfaces between components (APIs, data contracts) clearly specified? Would a developer know how to integrate with each piece?"}
                 {:name     "Data Model"
                  :criteria "Is the data model described? Are the key entities, their relationships, and storage strategy clear?"}
                 {:name     "Scalability"
                  :criteria "Does the design account for expected scale? Are the bottlenecks identified and addressed?"}
                 {:name     "Security"
                  :criteria "Are authentication, authorization, and data protection concerns addressed? Are user-facing security risks considered?"}
                 {:name     "Technology Choices"
                  :criteria "Are the technology choices justified and appropriate for the problem? Do they match the team's existing expertise?"}
                 {:name     "Implementation Risk"
                  :criteria "Are the key technical unknowns or risks identified? Is there a plan for the hardest parts?"}]})

(def default-definitions
  [intent-clarity-definition
   architecture-clarity-definition])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-score-definitions
  [db user-id]
  (persistence/get-score-definitions db user-id))

(defn get-score-definition
  [db definition-id]
  (persistence/get-score-definition db definition-id))

(defn create-score-definition!
  [db user-id body]
  (persistence/create-score-definition! db (assoc body :user-id user-id)))

(defn update-score-definition!
  [db definition-id updates]
  (persistence/update-score-definition! db definition-id updates))

(defn delete-score-definition!
  "Delete a score definition. Returns an error map if it's a default or not found."
  [db definition-id]
  (persistence/delete-score-definition! db definition-id))

(defn seed-default-definitions!
  "Seed the two default score definitions for a user if they don't already have them."
  [db user-id]
  (let [existing (persistence/get-score-definitions db user-id)
        existing-names (set (map :name existing))]
    (doseq [{:keys [name] :as defn} default-definitions]
      (when-not (contains? existing-names name)
        (log/infof "Seeding default score definition '%s' for user %s" name user-id)
        (persistence/create-score-definition! db (assoc defn :user-id user-id))))))
