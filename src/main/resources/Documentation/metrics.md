Metrics
=======

The @PLUGIN@ plugin reports the status of its internal components
to Gerrit metric system.

The metrics are all prefixed with @PLUGIN@ and then divided in groups,
depending on the component involved.

### plugins/@PLUGIN@/events

This prefix represents the component involved is the processing of incoming
ref-update events from Gerrit or other event-broker sources.

- `queued_before_startup` Counter of the number of events have been received
  when the plugin was still in its starting phase and not ready yet to process events.

### plugins/@PLUGIN@/tasks/<metric>/<source>

This prefix represents the tasks scheduling and execution system, also
known as _replication queue_. The replication task represent one operation
performed against a Git repository from a remote source. The task may involve
one or refs.

The `<metric>` field is can have one of the values described here below,
while the `<source>` represent the replication source endpoint.

> Bear in mind that the _replication queue_ is a lot more than a simple
> queueing system, as it contains the logic for selecting, merging, retrying
> and cancelling incoming tasks.

- `scheduled`: (counter) number of tasks triggered and scheduled for
  execution.

- `started`: (counter) number of tasks triggered and scheduled for
  execution.

- `rescheduled`: (counter) number of tasks re-scheduled for execution.

- `cancelled`: (counter) number of tasks that were previously scheduled
  and then cancelled before being executed

- `failed`: (counter) number of tasks executed but failed altogether or
  partially. A total failure is when the entire operation returned an
  error and none of the operations took place; a partial failure is when
  some of the operations in the tasks succeeded but other failed.

- `retrying`: (counter) number of tasks being retired for execution.

- `not_scheduled`: (counter) number of tasks which have been discarded before
  being executed, because redundant (duplicate of existing scheduled tasks)
  or not needed anymore (e.g. ref updated already by other means or other
  checks)

- `completed`: (counter) number of tasks completed successfully in full.

- `merged`: (counter) number of tasks not being executed because they had
  been consolidated with an existing scheduled task.

- `max_retries`: (counter) number of tasks that have reached their maximum
  retry count but never succeeded.

### plugins/@PLUGIN@

- `apply_object_latency`: (histogram) execution time statistics for the
  synchronous replication using the _apply-object_ REST-API.

- `apply_object_end_2_end_latency`: (historgram) execution time statistics
  for the end-2-end replication from when the event is triggered in
  Gerrit until the successful execution of the synchronous replication
  using the _apply-object_ REST-API.



