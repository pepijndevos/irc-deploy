(defproject irc-deploy "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [com.palletops/pallet "0.8.0-beta.7"]
                 [org.cloudhoist/pallet-vmfest "0.3.0-alpha.3"]
                 [org.clojars.tbatchelli/vboxjxpcom "4.2.4"]
                 [commons-codec "1.7"]]
  :plugins [[com.palletops/pallet-lein "0.6.0-beta.9"]]
  :aliases {"prod" ["pallet" "up" "--service" "VPS" "--roles" "prod"]
            "dev" ["pallet" "up" "--roles" "dev"]}
  :repositories {"sonatype" "http://oss.sonatype.org/content/repositories/releases"})
