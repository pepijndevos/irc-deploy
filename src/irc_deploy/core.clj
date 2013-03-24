(ns irc-deploy.core
  (:use [pallet.compute.vmfest :only [add-image]]
        [pallet.compute :only [instantiate-provider]]
        [pallet.api :only [group-spec, node-spec]]
        [pallet.crate :only [defplan get-settings assoc-settings]]
        [pallet.crate.automated-admin-user :only [automated-admin-user]]
        [pallet.actions :only [package package-manager package-source service with-service-restart service-script exec-script* exec-script user group remote-file directory]]
        [pallet.config-file.format :only [name-values sectioned-properties]]))

(def vmfest (instantiate-provider "vmfest"))
;(add-image vmfest "https://s3.amazonaws.com/vmfest-images/ubuntu-12.04.vdi.gz")

(defmulti install (fn [mngr pkg] mngr))

(defmethod install :npm [_ pkg]
  (exec-script ("npm" "install" "-g" ~pkg)))

(defplan nodejs []
  (package-source "nodejs" :aptitude {:url "ppa:chris-lea/node.js"})
  (package "nodejs"))

(defplan subway-conf []
  (group "subway" :action :create)
  (user "subway"
        :action :create
        :system true
        :group "subway"))

(defplan start-subway []
  (service-script "subway"
                  :service-impl :upstart
                  :local-file "resources/subway.upstart")
  (service "subway"
           :action :start
           :service-impl :upstart))

(defplan subway []
  (package "mongodb")
  (package "git")
  (nodejs)
  (install :npm "git://github.com/thedjpetersen/subway.git")
  (subway-conf)
  (start-subway))

(defplan ngircd-conf []
  (let [{:keys [host motd]} (get-settings :irc-server)]
    (remote-file
      "/etc/ngircd/ngircd.conf"
      :owner "irc"
      :group "irc"
      :content (sectioned-properties
                 {:global  {:Listen "127.0.0.1"
                            :Name host
                            :MotdPhrase motd
                            ;Keep this setting in sync with PIDFILE in /etc/init.d/ngircd
                            :PidFile "/var/run/ngircd/ngircd.pid"
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
                  :Limits  {:MaxConnectionsIP 0}}))))

(defplan ngircd []
  (package "ngircd")
  (with-service-restart "ngircd"
    (ngircd-conf)))

(defplan start-znc []
  (service-script "znc"
                  :service-impl :upstart
                  :local-file "resources/znc.upstart")
  (service "znc"
           :action :start
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
  (exec-script* "znc --makepem --datadir=/var/lib/znc/"))
              

(defplan znc []
  (package "znc")
  (znc-conf)
  (start-znc))

(defplan configure-irc []
  (package-manager :update)
  (package-manager :upgrade)
  (ngircd)
  (znc)
  (subway))

(defplan settings []
  (assoc-settings :irc-server {:host "irc.example.com", :motd "Welcome!"}))

(def ubuntu-group 
  (group-spec
    "ubuntu-vms" 
    :count 1
    :node-spec (node-spec
                 :image {:image-id :ubuntu-12.04}
    :phases {:bootstrap automated-admin-user
             :configure configure-irc
             :settings settings})))
