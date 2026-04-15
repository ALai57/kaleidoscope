(ns kaleidoscope.utils.local-files
  "Pure filesystem I/O for the Engineering Reviewer code context feature.
   No database dependencies. No knowledge of projects or matching logic."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.io File]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def NOISE-DIRS
  "Directory names whose entire subtrees are skipped during recursive collection.
   Hardcoded — users who need finer control specify explicit paths in local_paths."
  #{"node_modules" ".git" "target" "dist" "build" "out" "__pycache__"
    ".gradle" ".next" "coverage" "vendor" ".cache" ".idea" ".vscode"
    "tmp" "log" "logs"})

(def ^:private BINARY-EXTENSIONS
  #{".jar" ".class" ".png" ".jpg" ".jpeg" ".gif" ".svg" ".ico"
    ".zip" ".gz" ".tar" ".exe" ".so" ".dylib"
    ".woff" ".woff2" ".ttf" ".eot" ".pdf"})

(def ^:private SOURCE-EXTENSIONS
  #{".clj" ".cljc" ".cljs" ".py" ".go"
    ".ts" ".tsx" ".js" ".jsx"
    ".rb" ".rs" ".java" ".kt"
    ".ex" ".exs" ".hs" ".scala" ".cs" ".fs" ".ml"})

(def ^:private TIER-1-NAME-PREFIXES
  #{"main" "core" "schema" "routes" "models" "app" "server" "handler" "index"})

(def ^:private TIER-1-DOC-NAMES
  #{"README.md" "ARCHITECTURE.md" "DESIGN.md" "CLAUDE.md"})

(def ^:private CANONICAL-SOURCE-DIRS
  #{"src" "lib" "app" "pkg" "api" "handlers" "internal" "cmd" "source" "sources"})

(def ^:private METADATA-FILENAMES
  #{"deps.edn" "package.json" "Cargo.toml" "go.mod" "pyproject.toml" "mix.exs"
    "project.clj" "build.gradle" "pom.xml" "stack.yaml" "Makefile" "Dockerfile"})

(def ^:private MAX-COLLECT 2000)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn binary-extension?
  "True if the file has a known binary extension."
  [^File f]
  (let [name (.getName f)
        dot  (.lastIndexOf name ".")]
    (when (pos? dot)
      (contains? BINARY-EXTENSIONS (.toLowerCase (subs name dot))))))

(defn lock-file?
  "True for machine-generated lock files (*.lock, *-lock.json)."
  [^File f]
  (let [name (.getName f)]
    (or (str/ends-with? name ".lock")
        (str/ends-with? name "-lock.json"))))

(defn confined-path?
  "True if f's canonical path starts with root's canonical path.
   Prevents traversal via symlinks."
  [^File root ^File f]
  (str/starts-with? (.getCanonicalPath f) (.getCanonicalPath root)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Safe I/O
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn safe-slurp
  "Read up to max-chars characters from f.
   Returns {:ok \"...\" :truncated false} or {:error \"...\"}."
  [^File f max-chars]
  (try
    (let [content (slurp f)]
      (if (> (count content) max-chars)
        {:ok (subs content 0 max-chars) :truncated true}
        {:ok content :truncated false}))
    (catch Exception e
      {:error (.getMessage e)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority tier system
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn path-info
  "Extract the data needed for tier scoring from a File.
   Called at the I/O boundary — the only function here that takes two File args."
  [^File f ^File root]
  (let [abs-path    (.getCanonicalPath f)
        root-path   (.getCanonicalPath root)
        rel-path    (subs abs-path (inc (count root-path)))
        parts       (str/split rel-path (re-pattern File/separator))
        ;; parts = ["src" "kaleidoscope" "http_api" "projects.clj"]
        filename    (last parts)
        parent-dirs (set (butlast parts))
        depth       (count parts)]
    {:name        filename
     :depth       depth
     :parent-dirs parent-dirs}))

(defn tier-score
  "Return the priority tier (1–5) for a file described by path-info data.
   Pure function — no I/O, no File objects. Lower tier = read first."
  [{:keys [name depth parent-dirs]}]
  (let [stem (let [dot (.lastIndexOf ^String name ".")]
               (if (pos? dot) (.toLowerCase (subs name 0 dot)) (.toLowerCase name)))
        ext  (let [dot (.lastIndexOf ^String name ".")]
               (when (pos? dot) (.toLowerCase (subs name dot))))]
    (cond
      ;; Tier 1: entrypoints, schema files, key docs
      (or (contains? TIER-1-NAME-PREFIXES stem)
          (contains? TIER-1-DOC-NAMES name)
          (str/starts-with? (.toLowerCase name) "adr"))
      1

      ;; Tier 2: source-extension files inside a canonical source directory
      (and (contains? SOURCE-EXTENSIONS ext)
           (some CANONICAL-SOURCE-DIRS parent-dirs))
      2

      ;; Tier 3: source-extension files anywhere else
      (contains? SOURCE-EXTENSIONS ext)
      3

      ;; Tier 4: project metadata
      (or (contains? METADATA-FILENAMES name)
          (and ext (contains? #{".yml" ".yaml" ".toml"} ext)))
      4

      ;; Tier 5: everything else
      :else 5)))

(defn prioritize-files
  "Sort files by (tier-score, depth, name). Returns sorted seq of Files."
  [files ^File root]
  (sort-by (fn [^File f]
             (let [info (path-info f root)]
               [(tier-score info) (:depth info) (:name info)]))
           files))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Recursive collection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn collect-recursive
  "Recursively walk root, skipping NOISE-DIRS subtrees.
   Returns a flat vector of regular File objects.
   Caps at MAX-COLLECT files to bound walk time on large trees."
  [^File root]
  (let [result (volatile! (transient []))
        count  (volatile! 0)]
    (letfn [(walk [^File dir]
              (when (< @count MAX-COLLECT)
                (let [children (.listFiles dir)]
                  (when children
                    (doseq [^File child (sort-by #(.getName %) children)]
                      (when (< @count MAX-COLLECT)
                        (cond
                          (.isDirectory child)
                          (when-not (contains? NOISE-DIRS (.getName child))
                            (walk child))

                          (.isFile child)
                          (do (vswap! result conj! child)
                              (vswap! count inc)))))))))]
      (walk root))
    (when (>= @count MAX-COLLECT)
      (log/warnf "collect-recursive: hit %d file cap under %s — some files not collected"
                 MAX-COLLECT (.getAbsolutePath root)))
    (persistent! @result)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main read function
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn read-local-paths
  "Read files from a mix of file and directory paths.
   For directories: recursively collects, prioritises by tier, reads in order.
   Returns:
     :files    — [{:path \"...\" :content \"...\" :truncated false}]
     :not-read — paths collected but not reached before the total cap
     :skipped  — [{:path \"...\" :reason :binary|:lock|:traversal}]"
  [paths & {:keys [max-total max-per-file]
            :or   {max-total 50000 max-per-file 10000}}]
  (let [files-out   (volatile! [])
        not-read    (volatile! [])
        skipped     (volatile! [])
        total-chars (volatile! 0)]

    (doseq [path paths]
      (let [f (File. ^String path)]
        (cond
          (not (.exists f))
          (do (log/warnf "read-local-paths: path does not exist: %s" path)
              (vswap! skipped conj {:path path :reason :not-found}))

          (.isFile f)
          (cond
            (binary-extension? f)
            (vswap! skipped conj {:path path :reason :binary})

            (lock-file? f)
            (vswap! skipped conj {:path path :reason :lock})

            :else
            (if (>= @total-chars max-total)
              (vswap! not-read conj path)
              (let [{:keys [ok error truncated]} (safe-slurp f max-per-file)]
                (if error
                  (vswap! skipped conj {:path path :reason :read-error :message error})
                  (do (vswap! files-out conj {:path path :content ok :truncated truncated})
                      (vswap! total-chars + (count ok)))))))

          (.isDirectory f)
          (let [root       f
                all-files  (collect-recursive f)
                prioritised (prioritize-files all-files root)
                ;; Filter out skipped files first
                to-read    (volatile! [])
                _          (doseq [^File child prioritised]
                             (let [child-path (.getAbsolutePath child)]
                               (cond
                                 (binary-extension? child)
                                 (vswap! skipped conj {:path child-path :reason :binary})

                                 (lock-file? child)
                                 (vswap! skipped conj {:path child-path :reason :lock})

                                 (not (confined-path? root child))
                                 (vswap! skipped conj {:path child-path :reason :traversal})

                                 :else
                                 (vswap! to-read conj child))))]
            (doseq [^File child @to-read]
              (let [child-path (.getAbsolutePath child)]
                (if (>= @total-chars max-total)
                  (vswap! not-read conj child-path)
                  (let [{:keys [ok error truncated]} (safe-slurp child max-per-file)]
                    (if error
                      (vswap! skipped conj {:path child-path :reason :read-error :message error})
                      (do (vswap! files-out conj {:path child-path :content ok :truncated truncated})
                          (vswap! total-chars + (count ok)))))))))

          :else
          (vswap! skipped conj {:path path :reason :not-file-or-dir}))))

    {:files    @files-out
     :not-read @not-read
     :skipped  @skipped}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Formatting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn format-code-context
  "Assemble a <code_context> block from the result of read-local-paths.
   Concatenation of file contents happens here, not in read-local-paths."
  [{:keys [root files not-read skipped strategy]}]
  (let [sb (StringBuilder.)]
    (.append sb "<code_context>\n")
    (when root
      (.append sb (str "Root: " root "\n")))
    (.append sb (str "Strategy: " (or strategy "recursive") "\n"))

    ;; Files read
    (let [read-paths (map :path files)]
      (.append sb (str "Files read (" (count files) "): "
                       (str/join ", " (take 10 read-paths))
                       (when (> (count read-paths) 10) (str " ... +" (- (count read-paths) 10) " more"))
                       "\n")))

    ;; Truncated files
    (let [truncated (filter :truncated files)]
      (when (seq truncated)
        (.append sb (str "Truncated at 10k: "
                         (str/join ", " (map :path truncated)) "\n"))))

    ;; Cap-reached
    (when (seq not-read)
      (.append sb (str "Cap reached — not read (" (count not-read) " files): "
                       (str/join ", " (take 5 not-read))
                       (when (> (count not-read) 5) (str " ... +" (- (count not-read) 5) " more"))
                       "\n")))

    ;; Skipped binary
    (let [binary (filter #(= :binary (:reason %)) skipped)]
      (when (seq binary)
        (.append sb (str "Skipped binary (" (count binary) "): "
                         (str/join ", " (map :path binary)) "\n"))))

    ;; Skipped lock
    (let [locks (filter #(= :lock (:reason %)) skipped)]
      (when (seq locks)
        (.append sb (str "Skipped lock (" (count locks) "): "
                         (str/join ", " (map :path locks)) "\n"))))

    (.append sb "\n")

    ;; File contents
    (doseq [{:keys [path content truncated]} files]
      (.append sb (str "--- " path " ---\n"))
      (.append sb content)
      (when truncated
        (.append sb "\n[... truncated at 10k characters ...]"))
      (.append sb "\n\n"))

    (.append sb "</code_context>")
    (str sb)))
