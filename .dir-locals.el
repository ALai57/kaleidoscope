
((nil
  ;; Start CIDER REPL with the following aliases.
  ;;   :test  - Tests can run at REPL
  ;;   :dev   - Developer tooling included in REPL
  ;;   :build - Build can be run at REPL
  (cider-clojure-cli-global-options . "-A:test:dev:build")

  ;; Start Cider with Clojure version 12, so we can use
  ;; clojure.java.process instead of clojure.java.shell
  (cider-jack-in-auto-inject-clojure . "1.12.0-alpha3"
  ))
