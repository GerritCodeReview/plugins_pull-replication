package com.googlesource.gerrit.plugins.replication.pull;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.ioutil.HexFormat;
import com.google.gerrit.server.util.IdGenerator;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchRestApiClient;
import org.eclipse.jgit.transport.URIish;

import java.io.IOException;
import java.net.URISyntaxException;

import static com.googlesource.gerrit.plugins.replication.pull.ReplicationQueue.repLog;

public class DeleteProjectTask implements Runnable {
    interface Factory {
        DeleteProjectTask create(Source source, String uri, Project.NameKey project);
    }

    private final int id;
    private final Source source;
    private final String uri;
    private final Project.NameKey project;
    private final FetchRestApiClient.Factory fetchClientFactory;

    @Inject
    DeleteProjectTask(
            FetchRestApiClient.Factory fetchClientFactory,
            IdGenerator ig,
            @Assisted Source source,
            @Assisted String uri,
            @Assisted Project.NameKey project) {
        this.fetchClientFactory = fetchClientFactory;
        this.id = ig.next();
        this.uri = uri;
        this.source = source;
        this.project = project;
    }

    @Override
    public void run() {
        try {
            URIish urIish = new URIish(uri);
            fetchClientFactory.create(source).deleteProject(project, urIish);
            return;
        } catch (URISyntaxException | IOException e) {
            // TODO Do something
        }

        repLog.warn("Cannot delete project {}} on remote site {}.", project, uri);
    }

    @Override
    public String toString() {
        return String.format(
                "[%s] delete-project %s at %s", HexFormat.fromInt(id), project.get(), uri);
    }
}
