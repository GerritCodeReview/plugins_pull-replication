Metrics
=======

The @PLUGIN@ plugin reports the status of its internal components
to Gerrit metric system.

The metrics are all prefixed with @PLUGIN@ and then divided in groups,
depending on the component involved.

### plugins/@PLUGIN@/events

This prefix represents the component involved in the processing of incoming
ref-update events from Gerrit or other event-broker sources.

- `queued_before_startup` Counter of the number of events that have been received
  when the plugin was still in its starting phase and not ready yet to process events.

### plugins/@PLUGIN@/tasks/<metric>/<source>

This prefix represents the tasks scheduling and execution system, also
known as _replication queue_. The replication task represents one operation
performed against a Git repository from a remote source. The task may involve
one or more refs.

The `<metric>` field can have one of the values described here below,
while the `<source>` represent the replication source endpoint.

> Bear in mind that the _replication queue_ is a lot more than a simple
> queueing system, as it contains the logic for selecting, merging, retrying
> and cancelling incoming tasks.

- `scheduled`: (counter) number of tasks triggered and scheduled for
  execution.

- `in_flight`: (gauge) number of tasks currently being executed.

- `pending`: (gauge) number of tasks waiting to be executed.

- `started`: (counter) number of tasks started.

- `rescheduled`: (counter) number of tasks re-scheduled for execution.

- `cancelled`: (counter) number of tasks that were previously scheduled
  and then cancelled before being executed.

- `failed`: (counter) number of tasks executed but failed altogether or
  partially. A total failure is when the entire operation returned an
  error and none of the operations took place; a partial failure is when
  some of the operations in the tasks succeeded but other failed.

- `retrying`: (counter) number of tasks being retried for execution.

- `not_scheduled`: (counter) number of tasks which have been discarded before
  being executed, because redundant (duplicate of existing scheduled tasks)
  or not needed anymore (e.g. ref updated already by other means or other
  checks)

- `completed`: (counter) number of tasks completed successfully.

- `merged`: (counter) number of tasks not being executed because they had
  been consolidated with an existing scheduled task.

- `failed_max_retries`: (counter) number of tasks that have reached their maximum
  retry count but never succeeded.

### plugins/@PLUGIN@/fetch/refs/<metric>/<source>

Number of refs included in the Git fetch operation.

The `<metric>` field can have one of the values described here below,
while the `<source>` represent the replication source endpoint.

- `started`: (counter) number of refs for which fetch operation have started.

- `completed`: (counter) number of refs for which fetch operation have completed.

- `failed`: (counter) number of refs for which fetch operation have failed.

### plugins/@PLUGIN@

- `apply_object_latency`: (timer) execution time statistics for the
  synchronous replication using the _apply-object_ REST-API.

- `apply_object_end_2_end_latency`: (timer) execution time statistics
  for the end-2-end replication from when the event is triggered in
  Gerrit until the successful execution of the synchronous replication
  using the _apply-object_ REST-API.

- `apply_object_max_api_payload_reached`: (counter) number of times that
  the apply-object REST-API did fallback to the fetch REST-API because
  it reached its maximum payload to transfer.

- `replication_latency`: (timer) execution time statistics for the
  synchronous replication using a _git fetch_.

- `replication_end_2_end_latency`: (timer) execution time statistics
  for the end-2-end replication from when the event is triggered in
  Gerrit until the successful execution of the _git fetch_ for the
  associated refs.

- `replication_delay`: (timer) interval from when the ref-update event
  is triggered to the start of the _git fetch_ command.

- `replication_retries`: (counter) number of times that a replication task
  has been retried.


