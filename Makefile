
# Enable debug output
debug ?= 0

# DockerHub image to build FROM: openjdk:$(open_jdk_version)
open_jdk_version ?= jdk8u265-b01-centos
open_jdk_image ?= adoptopenjdk/openjdk8

#Version of sbt package to install: https://dl.bintray.com/sbt/debian/sbt-$(sbt_version).deb
# Must match entry in project/build.properties
# TODO: Pull version from the file
sbt_version ?= 1.3.3

# Which version of Mesos to run tests against 
mesos_version ?= 1.9.0

# Docker image tag
# This must exist as ${docker_image_name}:$(docker_image_tag) in either:
## Local docker image cache 
## DockerHub 
docker_image_tag=$(open_jdk_version)-$(sbt_version)
docker_image_name=mesos/sbt
docker_builder=builder
target_dir=target

quiet := @
sbt_debug_flag :=
ifneq ($(debug),0)
ifneq ($(debug),)
$(info Debug output enabled at level $(debug))
quiet =
sbt_debug_flag := -v
endif
endif

# Create a default target
.PHONY: no_targets__
@no_targets__: list

# List the make targets
.PHONY: list
list: 
	$(quiet) echo "List make targets:" &&\
	cat Makefile | grep "^[A-Za-z0-9_-]\+:" | grep -v "^_[A-Za-z0-9_-]\+" | awk '{print $$1}' | sed "s/://g" | sed "s/^/   /g" | sort

# Create the docker entrypoint file
.PHONY: _create-dockerfile
_create-dockerfile:
	$(quiet) \
	mkdir -p $(target_dir)                                                                                 &&\
	echo 'ARG OPENJDK_TAG=$(open_jdk_version)'                                                              > $(target_dir)/Dockerfile &&\
	echo 'ARG OPENJDK_NAME=$(open_jdk_image)'                                                              >> $(target_dir)/Dockerfile &&\
	echo 'FROM $${OPENJDK_NAME}:$${OPENJDK_TAG}'                                                           >> $(target_dir)/Dockerfile &&\
	echo 'ARG SBT_VERSION=$(sbt_version)'                                                                  >> $(target_dir)/Dockerfile &&\
	echo 'ARG MESOS_VERSION=$(mesos_version)'                                                              >> $(target_dir)/Dockerfile &&\
	echo 'ENV DOCKER_FROM=$${OPENJDK_NAME}:$${OPENJDK_TAG}'                                                >> $(target_dir)/Dockerfile &&\
	echo 'ENV SBT_VERSION=$${SBT_VERSION}'                                                                 >> $(target_dir)/Dockerfile &&\
	echo 'ENV MESOS_VERSION=$${MESOS_VERSION}'                                                             >> $(target_dir)/Dockerfile &&\
	echo 'RUN \'                                                                                           >> $(target_dir)/Dockerfile &&\
	echo '	curl -L -o /etc/yum.repos.d/bintray-sbt-rpm.repo https://bintray.com/sbt/rpm/rpm &&\'          >> $(target_dir)/Dockerfile &&\
	echo '	rpm -Uvh http://repos.mesosphere.io/el/7/noarch/RPMS/mesosphere-el-repo-7-1.noarch.rpm &&\'    >> $(target_dir)/Dockerfile &&\
	echo '	yum install -y sbt-$${SBT_VERSION} mesos-$${MESOS_VERSION} git'                                >> $(target_dir)/Dockerfile 


# Build local copy of docker image if public image doesn't exist
.PHONY: docker-build
docker-build: _create-dockerfile
	$(quiet) \
	docker build \
		--rm \
		--tag $(docker_image_name):$(docker_image_tag)  \
		-f $(target_dir)/Dockerfile \
		.	


# Create the docker entrypoint file
.PHONY: _create-entrypoint
_create-entrypoint:
	$(quiet) \
	mkdir -p $(target_dir) &&\
	echo "#!/bin/bash -eu"                                                               > $(target_dir)/entrypoint.sh &&\
	echo 'USER_NAME=$(docker_builder)'                                                  >> $(target_dir)/entrypoint.sh &&\
	echo 'echo "USER_UID=$${USER_UID}"'                                                 >> $(target_dir)/entrypoint.sh &&\
	echo 'echo "USER_GID=$${USER_GID}"'                                                 >> $(target_dir)/entrypoint.sh &&\
	echo 'if ! getent group $${USER_GID} &>/dev/null; then'                             >> $(target_dir)/entrypoint.sh &&\
	echo '  groupadd --non-unique -g $${USER_GID} $${USER_NAME}'                        >> $(target_dir)/entrypoint.sh &&\
	echo 'fi'                                                                           >> $(target_dir)/entrypoint.sh &&\
	echo 'if ! getent passwd $${USER_UID} &>/dev/null; then'                            >> $(target_dir)/entrypoint.sh &&\
	echo '  useradd --non-unique -u $${USER_UID} -g $${USER_GID} $${USER_NAME} -d /app' >> $(target_dir)/entrypoint.sh &&\
	echo 'fi'                                                                           >> $(target_dir)/entrypoint.sh &&\
	echo 'su $${USER_NAME} -c "sbt $$@"'                                                >> $(target_dir)/entrypoint.sh &&\
	chmod u+x $(target_dir)/entrypoint.sh


# Clean the target directories and sbt cache
# Also cleans files created when using docker-shell-root
.PHONY: deep-clean
deep-clean: 
	$(quiet) \
	sudo find . -type d -name target -prune -exec rm -r {} + &&\
	echo "Cleaning complete"

.PHONY: _sbt-run
_sbt-run: _create-entrypoint
	$(quiet) \
	mkdir -p $$HOME/.sbt $$HOME/.ivy2 $$HOME/.m2 &&\
	docker run -it --rm \
	  --entrypoint "/app/$(target_dir)/entrypoint.sh" \
	  -e USER_GID=$(shell id -g) \
	  -e USER_UID=$(shell id -u) \
	  -e HOME=/home/$(docker_builder) \
	  -v $$PWD:/app \
	  -v $$HOME/:/home/$(docker_builder)/ \
	  -v $$HOME/.sbt/:/app/.sbt/ \
	  -v $$HOME/.ivy2/:/app/.ivy2/ \
	  -v $$HOME/.m2/:/app/.m2/ \
	  -w /app \
	  $(docker_image_name):$(docker_image_tag) \
		$(sbt_debug_flag) $(sbt_command)

.PHONY: sbt-clean
sbt-clean:
	$(quiet) \
	$(MAKE) sbt_command=clean debug=$(debug) open_jdk_version=$(open_jdk_version) sbt_version=$(sbt_version) _sbt-run

.PHONY: sbt-compile
sbt-compile: 
	$(quiet) \
	$(MAKE) sbt_command=compile debug=$(debug) open_jdk_version=$(open_jdk_version) sbt_version=$(sbt_version) _sbt-run

.PHONY: sbt-test
sbt-test:
	$(quiet) \
	$(MAKE) sbt_command=test debug=$(debug) open_jdk_version=$(open_jdk_version) sbt_version=$(sbt_version) _sbt-run

.PHONY: sbt-package
sbt-package: 
	$(quiet) \
	$(MAKE) sbt_command=package debug=$(debug) open_jdk_version=$(open_jdk_version) sbt_version=$(sbt_version) _sbt-run &&\
	echo "JAR files created:" &&\
	find . -type f -name *.jar | grep target/scala

# Publish to current users local Maven repo: ~/.m2
.PHONY: sbt-publishM2
sbt-publishM2: 
	$(quiet) \
	$(MAKE) sbt_command=publishM2 debug=$(debug) open_jdk_version=$(open_jdk_version) sbt_version=$(sbt_version) _sbt-run &&\
	echo "JAR files created:" &&\
	find . -type f -name *.jar | grep target/scala

# Publish to current users local Ivy repo: ~/.ivy
.PHONY: sbt-publishLocal
sbt-publishLocal: 
	$(quiet) \
	$(MAKE) sbt_command=publishLocal debug=$(debug) open_jdk_version=$(open_jdk_version) sbt_version=$(sbt_version) _sbt-run &&\
	echo "JAR files created:" &&\
	find . -type f -name *.jar | grep target/scala

.PHONY: sbt-shell
sbt-shell:
	$(quiet) \
	$(MAKE) sbt_command=shell debug=$(debug) open_jdk_version=$(open_jdk_version) sbt_version=$(sbt_version) _sbt-run

# BEWARE: Compiling from this bash shell can create build artifacts owned by root; use deep-clean to remove
# Uses a separate set of sbt cache directories
.PHONY: docker-shell
docker-shell:
	$(quiet) \
	docker run -it --rm \
	  -v $$PWD:/app \
	  -v $$HOME/:/home/$(docker_builder)/ \
	  -w /app \
	  $(docker_image_name):$(docker_image_tag) \
		/bin/bash
    
