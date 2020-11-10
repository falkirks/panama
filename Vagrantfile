# -*- mode: ruby -*-
# vi: set ft=ruby :

# Derived from: https://github.com/CamFlow/vagrant/tree/master/basic-fedora

# All Vagrant configuration is done below. The "2" in Vagrant.configure
# configures the configuration version (we support older styles for
# backwards compatibility). Please don't change it unless you know what
# you're doing.
Vagrant.configure(2) do |config|
  # The most common configuration options are documented and commented below.
  # For a complete reference, please see the online documentation at
  # https://docs.vagrantup.com.

  # Every Vagrant development environment requires a box. You can search for
  # boxes at https://atlas.hashicorp.com/search.
  config.vm.box = "fedora/32-cloud-base"

  # Disable automatic box update checking. If you disable this, then
  # boxes will only be checked for updates when the user runs
  # `vagrant box outdated`. This is not recommended.
  # config.vm.box_check_update = false

  # Create a forwarded port mapping which allows access to a specific port
  # within the machine from a port on the host machine. In the example below,
  # accessing "localhost:8080" will access port 80 on the guest machine.
  # config.vm.network "forwarded_port", guest: 80, host: 8080

  # Create a private network, which allows host-only access to the machine
  # using a specific IP.
  # config.vm.network "private_network", ip: "192.168.33.10"

  # Create a public network, which generally matched to bridged network.
  # Bridged networks make the machine appear as another physical device on
  # your network.
  # config.vm.network "public_network"

  # Share an additional folder to the guest VM. The first argument is
  # the path on the host to the actual folder. The second argument is
  # the path on the guest to mount the folder. And the optional third
  # argument is a set of non-required options.
  # config.vm.synced_folder "../data", "/vagrant_data"
  config.vm.synced_folder "./guest", "/vagrant", create: true, owner: 'vagrant', disabled: false, type: 'virtualbox'

  # Provider-specific configuration so you can fine-tune various
  # backing providers for Vagrant. These expose provider-specific options.
  # Example for VirtualBox:
  #
  config.vm.provider "virtualbox" do |vb|
   # Display the VirtualBox GUI when booting the machine
   vb.gui = false
   # Customize the amount of memory on the VM:
   vb.memory = 10000
   # Customize CPU cap
   vb.customize ["modifyvm", :id, "--cpuexecutioncap", "70"]
   # Customize number of CPU
   vb.cpus = 4
   # Customize VM name
   # vb.name = "CamFlow-SPADE"
  end
  #
  # View the documentation for the provider you are using for more
  # information on available options.

  # Define a Vagrant Push strategy for pushing to Atlas. Other push strategies
  # such as FTP and Heroku are also available. See the documentation at
  # https://docs.vagrantup.com/v2/push/atlas.html for more information.
  # config.push.define "atlas" do |push|
  #   push.app = "YOUR_ATLAS_USERNAME/YOUR_APPLICATION_NAME"
  # end

  # Enable provisioning with a shell script. Additional provisioners such as
  # Puppet, Chef, Ansible, Salt, and Docker are also available. Please see the
  # documentation for more information about their specific syntax and use.
  config.vm.provision "shell", inline: <<-SHELL
    # Update installed packages
    sudo dnf -y -v upgrade

    # install make
    sudo dnf -y install make wget unzip zip

  	# Install Java
  	sudo dnf -y install java-11-openjdk-devel.x86_64
  	# Download SPADE
  	sudo dnf -y install audit fuse-devel fuse-libs git iptables kernel-devel-`uname -r` lsof uthash-devel curl cmake clang
  	wget https://github.com/ashish-gehani/SPADE/archive/master.zip
  	unzip master.zip
  	mv SPADE-master SPADE
  	cd SPADE
  	# Build SPADE
  	./configure
  	make KERNEL_MODULES=false

  	# Download and Install CamFlow
  	curl -s https://packagecloud.io/install/repositories/camflow/provenance/script.rpm.sh | sudo bash
  	sudo dnf -y install camflow nano

  	# Update grub to set the camflow kernel to default kernel at boot
  	sudo sed -i 's/saved/1/g' /etc/default/grub
  	sudo grub2-mkconfig -o /boot/grub2/grub.cfg

  	# Update camflow config as required by SPADE
  	# Set duplicate for vertices to true
  	sudo sed -i 's/duplicate=false/duplicate=true/' /etc/camflow.ini
  	# Comment out the default w3c format
  	sudo sed -i 's/format=w3c/;format=w3c/' /etc/camflowd.ini
  	# Uncomment the format recognized by SPADE
  	sudo sed -i 's/;format=spade_json/format=spade_json/' /etc/camflowd.ini
  	# Use FIFO
  	sudo sed -i 's/;output=fifo/output=fifo/' /etc/camflowd.ini
  	sudo sed -i 's/output=log/;output=log/' /etc/camflowd.ini

  	# Enable camflow service
  	sudo systemctl enable camconfd.service
  	sudo systemctl enable camflowd.service


    # install Docker
    sudo dnf -y install docker
    # enable docker
    sudo systemctl enable docker
    # need to do that to get docker to work...
    # see (https://github.com/docker/cli/issues/297#issuecomment-547022631)
    sudo grubby --update-kernel=ALL --args="systemd.unified_cgroup_hierarchy=0"

    # make it so that /tmp is cleaned on reboot
    touch /etc/tmpfiles.d/boot.conf
    echo 'R! /tmp 1777 root root ~0' > /etc/tmpfiles.d/boot.conf
  SHELL
  config.vm.define "leader" do |leader|
    config.vm.hostname = "leader.local"
    config.vm.network "public_network", ip: "192.168.0.1", hostname: true
  end

  config.vm.define "alice" do |alice|
    config.vm.hostname = "alice.local"
    config.vm.network "public_network", ip: "192.168.0.2", hostname: true
  end

  config.vm.define "bob" do |bob|
    config.vm.hostname = "bob.local"
    config.vm.network "public_network", ip: "192.168.0.3", hostname: true
  end
end
