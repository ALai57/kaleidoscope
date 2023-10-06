(ns kaleidoscope.http-api.portfolio
  (:require [kaleidoscope.api.portfolio :as portfolio-api]
            [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [ok]]
            [kaleidoscope.api.portfolio :as portfolio]))

(def example-portfolio-response
  {:nodes [{:id 1,
            :name "HELIX",
            :type "organization",
            :url "https://helix.northwestern.edu",
            :image-url "images/nu-helix-logo.svg",
            :description "Northwestern science outreach magazine",
            :tags ""}
           {:id 2,
            :name "YMCA",
            :type "organization",
            :url "http://www.mcgawymca.org/",
            :image-url "images/ymca-logo.svg",
            :description "YMCA",
            :tags ""}
           {:id 3,
            :name "VAI",
            :type "organization",
            :url "www.hnvi.org",
            :image-url "images/vai-logo.svg",
            :description "Vietnamese Association of Illinois",
            :tags ""}
           {:id 4,
            :name "ChiPy",
            :type "organization",
            :url "https://www.chipy.org",
            :image-url "images/chipy-logo.svg",
            :description "Chicago Python User Group",
            :tags ""}
           {:id 5,
            :name "ChiHackNight",
            :type "organization",
            :url "https://chihacknight.org/",
            :image-url "images/chi-hack-night-logo.svg",
            :description "Chicago Civic Hacking",
            :tags ""}
           {:id 6,
            :name "Center for Leadership",
            :type "organization",
            :url "https://lead.northwestern.edu/leadership/index.html",
            :image-url "images/center-for-leadership-logo.svg",
            :description "Northwestern Center for Leadership",
            :tags ""}
           {:id 7,
            :name "MGLC",
            :type "organization",
            :url "https://www.mccormick.northwestern.edu/graduate-leadership-council/",
            :image-url "images/mglc-logo.svg",
            :description "McCormick Graduate Leadership Council",
            :tags ""}
           {:id 8,
            :name "Periscope Data",
            :type "skill",
            :url "",
            :image-url "images/periscope-logo.svg",
            :description "Analytics tool for dashboarding and data visualization....",
            :tags "Analytics Tools"}
           {:id 9,
            :name "Sumologic",
            :type "skill",
            :url "",
            :image-url "images/sumologic-logo.svg",
            :description "Analytics tool for collecting/analyzing event logs.",
            :tags "Analytics Tools"}
           {:id 10,
            :name "Heap Analytics",
            :type "skill",
            :url "",
            :image-url "images/heap-logo.svg",
            :description
            "Analytics tool for collecting/analyzing user behavior data and funnel conversion.",
            :tags "Analytics Tools"}
           {:id 11,
            :name "Jupyter Notebooks",
            :type "skill",
            :url "",
            :image-url "images/jupyter-logo.svg",
            :description "Analytics tool for data analytics/data science.",
            :tags "Analytics Tools"}
           {:id 12,
            :name "Pandas",
            :type "skill",
            :url "",
            :image-url "images/pandas-logo.svg",
            :description "Python package for manipulating data.",
            :tags "Analytics Tools"}
           {:id 13,
            :name "The experiment that shocked the world",
            :type "project",
            :url "https://helix.northwestern.edu/article/experiment-shocked-world",
            :image-url "images/nu-helix-logo.svg",
            :description
            "A short article on Luigi Galvani and his discovery of animal electricity.",
            :tags ""}
           {:id 14,
            :name "Project SOAR",
            :type "project",
            :url
            "http://www.mcgawymca.org/youth-teens/after-school-care-activities/mentoring/project-soar-news/",
            :image-url "images/ymca-logo.svg",
            :description "Youth mentoring through the YMCA",
            :tags ""}
           {:id 15,
            :name "Teaching ESL and Citizenship",
            :type "project",
            :url "www.hnvi.org",
            :image-url "images/vai-logo.svg",
            :description
            "Teaching conversational English and preparation for the citizenship exam.",
            :tags ""}
           {:id 16,
            :name "ChiPy Mentorship Program",
            :type "project",
            :url "https://chipymentor.org",
            :image-url "images/chipy-logo.svg",
            :description "Mentee in a Python mentorship program.",
            :tags ""}],
   :links [{:id 1,
            :name-1 "The experiment that shocked the world",
            :relation "wrote for",
            :name-2 "HELIX",
            :description ""}
           {:id 2,
            :name-1 "Pandas",
            :relation "used at",
            :name-2 "ChiPy",
            :description ""}
           {:id 3,
            :name-1 "Jupyter Notebooks",
            :relation "learned at",
            :name-2 "ChiPy Mentorship Program",
            :description ""}
           {:id 4,
            :name-1 "Teaching ESL and Citizenship",
            :relation "performed at",
            :name-2 "VAI",
            :description ""}]})

(def reitit-portfolio-routes
  ["/projects-portfolio"
   {:tags     ["project-portfolio"]
    ;; For testing only - this is a mechanism to always get results from a particular
    ;; host URL.
    ;;
    ;;:host      "andrewslai.localhost"
    :get {:summary     "A user's portfolio of projects [Alpha]"
          :responses   {200 {:description "Projects portfolio"
                             :content     {"application/json"
                                           {:schema   portfolio/Portfolio
                                            :examples {"example-portfolio-response"
                                                       {:summary "Example Portfolio response"
                                                        :value   example-portfolio-response}}}}}}

          :handler (fn [{:keys [components parameters] :as request}]
                     (ok (portfolio-api/get-portfolio (:database components))))}}])
