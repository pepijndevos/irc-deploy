(ns irc-deploy.core
  (:use [pallet.api :only [group-spec server-spec node-spec]]
        [pallet.crate :only [defplan target-name]]
        [pallet.crate.automated-admin-user :only [automated-admin-user]]
        [pallet.actions :only [package package-manager package-source service with-service-restart service-script exec-script* exec-script user group remote-file directory remote-directory]]
        [pallet.stevedore :only [chain-commands]]
        [pallet.config-file.format :only [name-values sectioned-properties]]))

(defplan nodejs []
  (package-source "nodejs" :aptitude {:url "ppa:chris-lea/node.js"})
  (package "nodejs"))

(defplan kiwi-conf []
  (group "kiwi" :action :create)
  (user "kiwi"
        :action :create
        :home "/var/lib/kiwi"
        :create-home true
        :system true
        :group "kiwi"))

(defplan start-kiwi []
  (service-script "kiwiirc"
                  :service-impl :upstart
                  :local-file "resources/kiwi.upstart")
  (service "kiwiirc"
           :action :restart
           ;:if-stopped true
           :service-impl :upstart))

(defplan kiwi []
  (nodejs)
  (remote-directory
    "/var/lib/kiwi"
    :url "https://github.com/pepijndevos/KiwiIRC/archive/development.tar.gz"
    :owner "kiwi"
    :group "kiwi")
  (kiwi-conf)
  (exec-script ("cd" "/var/lib/kiwi/")
               ("npm" "install"))
  (start-kiwi))

(defplan ngircd-conf []
  (remote-file
    "/etc/ngircd/ngircd.conf"
    :owner "irc"
    :group "irc"
    :content (sectioned-properties
               {:global  {:Listen "127.0.0.1"
                          :Name target-name
                          ;Keep this setting in sync with PIDFILE in /etc/init.d/ngircd
                          :PidFile "/var/run/ircd/ngircd.pid"
                          ;Keep this setting in sync with DAEMONUSER in /etc/init.d/ngircd
                          :ServerGID "irc"
                          ;Keep this setting in sync with DAEMONUSER in /etc/init.d/ngircd
                          :ServerUID "irc"
                          ;Not required by server but by RFC!
                          :AdminInfo1 "A private server"
                          :AdminInfo2 "The Internet"
                          :AdminEMail (str "admin@" host)}
                :Options {:PAM "no"
                          :SyslogFacility "local1"}
                :Limits  {:MaxConnectionsIP 0}})))

(defplan ngircd []
  (package-source "ngircd" :aptitude {:url "http://debian.barton.de/debian"
                                      :release "lenny"
                                      :key-url "http://debian.barton.de/debian/archive-key.gpg"})
  (package "ngircd")
  (ngircd-conf)
  (service "ngircd" :action :restart))

(defplan start-znc []
  (service-script "znc"
                  :service-impl :upstart
                  :local-file "resources/znc.upstart")
  (service "znc"
           :action :restart
           :service-impl :upstart))

(defplan znc-conf []
  (group "znc" :action :create)
  (user "znc"
        :action :create
        :create-home true
        :home "/var/lib/znc"
        :system true
        :group "znc")
  (directory "/var/lib/znc/configs/"
             :owner "znc"
             :group "znc")
  (remote-file "/var/lib/znc/configs/znc.conf"
               :owner "znc"
               :group "znc"
               :local-file "resources/znc.conf")
  (exec-script ("znc" "--makepem" "--datadir" "/var/lib/znc/")))
              

(defplan znc []
  (package "znc")
  (znc-conf)
  (start-znc))

(defplan configure-irc []
  (package-manager :update)
  (package-manager :upgrade)
  (ngircd)
  (znc)
  (kiwi))

(def irc-server 
  (group-spec
    "irc-server" 
    :node-spec (node-spec
                 :image {:image-id :ubuntu-12.04}
    :phases {:bootstrap automated-admin-user
             :configure configure-irc})))

(def web-server
  (group-spec
    "web-server"
    :extends [nginx ngircd znc kiwi]))
