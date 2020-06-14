(defproject andrewslai "0.0.1"
  :description "Template for full stack development in Clojure"
  :dependencies [[buddy/buddy-auth "2.2.0"]
                 [buddy/buddy-hashers "1.4.0"]
                 [buddy/buddy-sign "3.1.0"]
                 [cheshire "5.10.0"]
                 ;; TODO: clean up for advanced opt.
                 [clj-commons/secretary "1.2.4"]
                 [clj-http "3.10.0"]
                 [cljs-ajax "0.8.0"]
                 [cljs-http "0.1.46"]
                 [cljsjs/react "16.13.0-0"]
                 [cljsjs/react-dom "16.13.0-0"]
                 [cljsjs/react-bootstrap "1.0.0-beta.14-0"] ;; latest release
                 [cljsjs/react-pose "1.6.4-1"]
                 [cljsjs/slate "0.33.6-0"]
                 [cljsjs/slate-react "0.12.6-0"]
                 [cljsjs/slate-html-serializer "0.6.3-0"]
                 [cljsjs/zxcvbn "4.4.0-1"]
                 [crypto-password "0.2.1"]
                 [com.nulab-inc/zxcvbn "1.3.0"]
                 [io.zonky.test/embedded-postgres "1.2.6" :scope "test"]
                 [coreagile/defenv "1.0.9"]
                 [day8.re-frame/tracing "0.5.3"]
                 [hiccup "1.0.5"]
                 [hickory "0.7.1"]
                 [honeysql "0.9.10"]
                 [http-kit "2.3.0"]
                 [metosin/compojure-api "1.1.12" :upgrade false]
                 [migratus "1.2.8"]
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.597"]
                 [org.clojure/core.async "1.0.567"
                  :exclusions [org.clojure/tools.reader]]
                 [org.clojure/data.csv "1.0.0"]
                 [org.clojure/data.codec "0.1.1"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [org.clojure/java.data "1.0.64"]
                 [org.postgresql/postgresql "42.2.11"]
                 [re-frame "0.12.0"]
                 [reagent "0.10.0"]
                 [ring "1.8.0"]
                 [ring/ring-mock "0.4.0"]
                 [sablono "0.8.6"]
                 [slingshot "0.12.2"]
                 [com.taoensso/timbre "4.10.0"]]

  :plugins [[lein-figwheel "0.5.19"]
            [lein-cljsbuild "1.1.7"]
            [lein-doo "0.1.10"]]

  ;; Used to make this compatible with Java 11
  :managed-dependencies [[org.clojure/core.rrb-vector "0.1.1"]
                         [org.flatland/ordered "1.5.7"]
                         [io.zonky.test.postgres/embedded-postgres-binaries-linux-amd64-alpine "10.6.0" :scope "test"]
                         [io.zonky.test.postgres/embedded-postgres-binaries-linux-amd64 "10.6.0" :scope "test"]]

  :ring {:handler andrewslai.clj.handler/figwheel-app}
  :aot :all
  :uberjar-name "andrewslai.jar"
  :main andrewslai.clj.handler
  :figwheel {:ring-handler andrewslai.clj.handler/figwheel-app
             :css-dirs ["resources/public/css"]}
  :clean-targets ^{:protect false} [:target-path "resources/public/js/compiled"]

  ;; See lein-doo documentation for installing Karma test runner
  :doo {:paths {:karma "test_runner/node_modules/karma/bin/karma"}
        :alias {:default [:chrome-headless]}
        :build "dev-test"}

  :cljsbuild
  {:builds
   {:dev
    {:source-paths ["src/andrewslai/cljs"]
     :figwheel {:open-urls ["http://localhost:3449/"]
                :on-jsload "andrewslai.cljs.core/main"}
     :compiler {:main andrewslai.cljs.core
                :asset-path "js/compiled/out_andrewslai"
                :optimizations :none
                :output-to "resources/public/js/compiled/andrewslai.js"
                :output-dir "resources/public/js/compiled/out_andrewslai"
                :source-map true
                :source-map-timestamp true}}

    :dev-test
    {:source-paths ["src/andrewslai/cljs" "test/andrewslai/cljs"]
     :compiler {:asset-path "resources/public/js/test/out_andrewslai_test"
                :main andrewslai.cljs.test-runner
                :optimizations :whitespace
                :output-to "resources/public/js/test/andrewslai_test.js"
                :output-dir "resources/public/js/test/out_andrewslai_test"}}

    :prod
    {:source-paths ["src/andrewslai/cljs"]
     :compiler {:main andrewslai.cljs.core
                :asset-path "js/compiled/out_andrewslai"
                :optimizations :advanced
                :output-to "resources/public/js/compiled/andrewslai.js"
                :output-dir "resources/public/js/compiled/out_prod"
                :source-map-timestamp true}}}

   }

  :profiles
  {:dev {:dependencies [[binaryage/devtools "1.0.0"]
                        [cider/piggieback "0.4.2"]
                        [figwheel-sidecar "0.5.19"]]
         :source-paths ["src/andrewslai/cljs"]
         :repl-options {:nrepl-middleware
                        [cider.piggieback/wrap-cljs-repl]}
         :aliases {"migratus" ["run" "-m" andrewslai.clj.persistence.migrations]}
         :plugins [[lein-ancient "0.6.15"]
                   [lein-bikeshed "0.5.2"]
                   [lein-kibit "0.1.8"]
                   [lein-ring "0.12.5"]]}

   :prod {:source-paths ["src/andrewslai/cljs"]}

   :dev-test {:source-paths ["src/andrewslai/cljs" "test/andrewslai/cljs"]}

   :upload {:dependencies [[clj-postgresql "0.7.0"]
                           [prismatic/plumbing "0.5.5"]]
            :repl-options {:nrepl-middleware
                           [cider.piggieback/wrap-cljs-repl]}
            :plugins [[lein-ancient "0.6.15"]
                      [lein-bikeshed "0.5.2"]
                      [lein-kibit "0.1.8"]
                      [lein-ring "0.12.5"]]}

   :uberjar {:source-paths ["src/andrewslai/cljs"]
             :cljsbuild
             {:builds {:deploy
                       {:source-paths ["src/andrewslai/cljs"]
                        :jar true
                        :compiler
                        {:main andrewslai.cljs.core
                         :asset-path "js/compiled/out_andrewslai"
                         :optimizations :advanced
                         :output-to "resources/public/js/compiled/andrewslai.js"
                         :output-dir "resources/public/js/compiled/out_deploy"
                         :source-map-timestamp true}}}}
             :prep-tasks ["compile" ["cljsbuild" "once" "deploy"]]}})
