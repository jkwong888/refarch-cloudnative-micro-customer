#!/bin/bash
set -e

# find the java heap size as 50% of container memory using sysfs, or 512m whichever is less
max_heap=`echo "512 * 1024 * 1024" | bc`
if [ -r "/sys/fs/cgroup/memory/memory.limit_in_bytes" ]; then
    mem_limit=`cat /sys/fs/cgroup/memory/memory.limit_in_bytes`
    if [ ${mem_limit} -lt ${max_heap} ]; then
        max_heap=${mem_limit}
    fi
fi
max_heap=`echo "(${max_heap} / 1024 / 1024) / 2" | bc`
export JAVA_OPTS="${JAVA_OPTS} -Xmx${max_heap}m"

# Set basic java options
export JAVA_OPTS="${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom"

# Load agent support if required
#source ./agents/newrelic.sh

# open the secrets
if [ ! -z "${HS256_KEY}" ]; then
  hs256_key=${HS256_KEY}
else
  hs256_key=`cat /var/run/secrets/hs256-key/key`
fi
JAVA_OPTS="${JAVA_OPTS} -Djwt.sharedSecret=${hs256_key}"

if [ ! -z "${couchdb}" ]; then
  cloudant_username=`echo ${couchdb} | jq -r '.username'`
  cloudant_password=`echo ${couchdb} | jq -r '.password'`
  cloudant_url=`echo ${couchdb} | jq -r  '.url'`
  JAVA_OPTS="${JAVA_OPTS} -Dcloudant.username=${cloudant_username} -Dcloudant.password=${cloudant_password} -Dcloudant.url=${cloudant_url}"
fi

echo "Starting with Java Options ${JAVA_OPTS}"

# Start the application
exec java ${JAVA_OPTS} -jar /app.jar
