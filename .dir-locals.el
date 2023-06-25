;;; Directory Local Variables            -*- no-byte-compile: t -*-
;;; For more information see (info "(emacs) Directory Variables")

;; Start CIDER REPL with the following aliases.
;;   :test  - Tests can run at REPL
;;   :dev   - Developer tooling included in REPL
;;   :build - Build can be run at REPL

;; Start Cider with Clojure version 12, so we can use
;; clojure.java.process instead of clojure.java.shell

((nil . ((cider-preferred-build-tool . clojure-cli)
         (cider-clojure-cli-global-options . "-A:test:dev:build")
         (cider-jack-in-auto-inject-clojure . "1.12.0-alpha3")
         (eval . (progn
                   (make-variable-buffer-local 'cider-jack-in-nrepl-middlewares)
                   (add-to-list 'cider-jack-in-nrepl-middlewares
                                "portal.nrepl/wrap-portal"))))))
