#!/bin/bash -e

while [ $# -ne 0 ]; do
  case "$1" in
  "--help")
    echo "Usage: bash $0 [--option $value]"
    echo
    echo "[--replication_log_files]      comma-separated list of replication_log files"
    echo "[--pull_replication_log_files] comma-separated list of pull_replication_log files"
    echo
    exit 0
    ;;
  "--replication_log_files")
    REPLICATION_LOG_FILES=$2
    shift
    shift
    ;;
  "--pull_replication_log_files")
    PULL_REPLICATION_LOG_FILES=$2
    shift
    shift
    ;;
  *)
    echo "Unknown option argument: $1"
    shift
    shift
    ;;
  esac
done

REPLICATION_TMP_COMPLETED=/tmp/replication_id_completed_${RANDOM}
grep "completed in" "$REPLICATION_LOG_FILES" | perl -pe 's/.*pushOneId="(.+)".*/$1/' >"$REPLICATION_TMP_COMPLETED"
REPLICATION_SCHEDULED_TRIPLETS=$(grep scheduled "$REPLICATION_LOG_FILES" | cut -d ' ' -f4,6 | tr -d '[]' | tr ' ' ':') # test:refs/multi-site/version:ef40a728

TMP_PULL_COMPLETED=/tmp/pull_replication_id_completed_${RANDOM}
grep -E 'Replication from.+completed' "$PULL_REPLICATION_LOG_FILES" | cut -d' ' -f3 | tr -d '[]' >"$TMP_PULL_COMPLETED"
SCHEDULED_PULL_TRIPLETS=$(grep scheduled "$PULL_REPLICATION_LOG_FILES" | cut -d' ' -f5,7,9 | perl -pe 's/.*\[(.+)\].*:refs(.+)\s(.+)/$3:refs$2:$1/')                                                                #test:refs/changes/21/21/1:bbff3501
APPLIED_PULL_COUNTS=$(grep -E 'Apply.+completed' "$PULL_REPLICATION_LOG_FILES" | perl -pe 's/.*for project (\S+),.+ref name (\S+) completed.*/$1:$2/' | sort | uniq -c | perl -pe 's/.*(\d+) (.+):(\S+)/$2:$3:$1/') #test:refs/changes/21/21/1:2

function arrayGet() {
  local array=$1 index=$2
  local i="${array}_$index"
  printf '%s' "${!i}"
}

function toKey() {
  local string=$1
  echo -n "$string" | base64 | tr '=' '_'
}

function fromKey() {
  local key=$1
  echo -n $key | tr '_' '=' | base64 -d
}

for triplet in $REPLICATION_SCHEDULED_TRIPLETS; do
  projectRef=$(echo "$triplet" | cut -d':' -f1,2)
  sessionId=$(echo "$triplet" | cut -d':' -f3)
  projectKey=$(toKey "$projectRef")

  declare "encountered_$projectKey=1"

  if grep -q "$sessionId" $REPLICATION_TMP_COMPLETED; then
    completed=$(arrayGet completed "$projectKey")
    cur="${completed:-0}"
    declare "completed_$projectKey=$(expr "$cur" '+' 1)"

  else
    failed=$(arrayGet failed "$projectKey")
    cur="${failed:-0}"
    declare "failed_$projectKey=$(expr "$cur" '+' 1)"
  fi
done

#project | ref       | repl | pull-repl
#test    | refs/meta | 10   | 10
echo "***** projects *****"
for replicated in ${!encountered_@}; do
  projectKey=$(echo "$replicated" | cut -d '_' -f2-)
  projectRef=$(fromKey "$projectKey")

  total_completed=$(arrayGet completed "$projectKey")
  total_completed=${total_completed:-0}

  total_failed=$(arrayGet failed "$projectKey")
  total_failed=${total_failed:-0}

  echo "$projectRef | OK: $total_completed | KO: $total_failed"
done
