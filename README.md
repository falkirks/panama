# Panama

This is the source code of Panama.

More information on [CamFlow](http://camflow.org/) and
[SPADE](https://github.com/ashish-gehani/SPADE/wiki) are available online.
Please, do report any issue to the
[SPADE](https://github.com/ashish-gehani/SPADE/issues) and
[CamFlow](https://github.com/CamFlow/camflow-dev/issues) teams.

## Installation

```
git clone https://github.com/falkirks/panama.git
vagrant up
vagrant halt
vagrant up
# make sure the machine boot under the camflow kernel
```

Note: the installation process can take an extended amount of time depending on your internet connection speed.
Note2: the rpm packages are graciously hosted on [packagecloud.io](https://packagecloud.io/camflow/provenance).

## Testing Installation of CamFlow

``` shell
vagrant ssh
# check installed version against CamFlow head
camflow -v
uname -r
# check services
journalctl -b | grep camflowd # audit service logs
journalctl -b | grep camconfd # configuration service logs
```

## Configuring CamFlow

The capture policy can be modified by editing `/etc/camflow.ini` (e.g. `sudo nano /etc/camflow.ini`).

Provenance publication can be modified by editing `/etc/camflowd.ini` (e.g. `sudo nano /etc/camflowd.ini`).

Please see our [documentation](http://camflow.org) for more details.

Reboot the machine for the new configuration to take effect (alternatively you can restart the associated services).

