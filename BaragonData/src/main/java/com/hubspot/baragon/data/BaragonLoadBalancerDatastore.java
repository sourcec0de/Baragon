package com.hubspot.baragon.data;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import com.hubspot.baragon.models.BaragonGroup;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hubspot.baragon.models.BaragonAgentMetadata;

@Singleton
public class BaragonLoadBalancerDatastore extends AbstractDataStore {
  private static final Logger LOG = LoggerFactory.getLogger(BaragonLoadBalancerDatastore.class);

  public static final String LOAD_BALANCER_GROUPS_FORMAT = "/load-balancer";
  public static final String LOAD_BALANCER_GROUP_FORMAT = LOAD_BALANCER_GROUPS_FORMAT + "/%s";
  public static final String LOAD_BALANCER_GROUP_HOSTS_FORMAT = LOAD_BALANCER_GROUP_FORMAT + "/hosts";
  public static final String LOAD_BALANCER_GROUP_HOST_FORMAT = LOAD_BALANCER_GROUP_HOSTS_FORMAT + "/%s";

  public static final String LOAD_BALANCER_BASE_PATHS_FORMAT = LOAD_BALANCER_GROUPS_FORMAT + "/%s/base-uris";
  public static final String LOAD_BALANCER_BASE_PATH_FORMAT = LOAD_BALANCER_BASE_PATHS_FORMAT + "/%s";

  @Inject
  public BaragonLoadBalancerDatastore(CuratorFramework curatorFramework, ObjectMapper objectMapper) {
    super(curatorFramework, objectMapper);
  }

  public LeaderLatch createLeaderLatch(String clusterName, BaragonAgentMetadata agentMetadata) {
    try {
      return new LeaderLatch(curatorFramework, String.format(LOAD_BALANCER_GROUP_HOSTS_FORMAT, clusterName), objectMapper.writeValueAsString(agentMetadata));
    } catch (JsonProcessingException e) {
      throw Throwables.propagate(e);
    }
  }

  public Collection<BaragonGroup> getLoadBalancerGroups() {
    final Collection<String> nodes = getChildren(LOAD_BALANCER_GROUPS_FORMAT);

    if (nodes.isEmpty()) {
      return Collections.emptyList();
    }
    final Collection<BaragonGroup> groups = Lists.newArrayListWithCapacity(nodes.size());

    for (String node : nodes) {
      try {
        groups.addAll(readFromZk(String.format(LOAD_BALANCER_GROUP_FORMAT, node), BaragonGroup.class).asSet());
      } catch (Exception e) {
        LOG.error(String.format("Could not fetch info for group %s due to error %s", node, e));
      }
    }

    return groups;
  }

  public Optional<BaragonGroup> getLoadBalancerGroup(String name) {
    try {
      return readFromZk(String.format(LOAD_BALANCER_GROUP_FORMAT, name), BaragonGroup.class);
    } catch (RuntimeException e) {
      if (e.getMessage().contains("No content")) {
        return Optional.absent();
      }
      throw Throwables.propagate(e);
    }
  }

  public BaragonGroup addSourceToGroup(String name, String source) {
    Optional<BaragonGroup> maybeGroup = getLoadBalancerGroup(name);
    BaragonGroup group;
    if (maybeGroup.isPresent()) {
      group = maybeGroup.get();
      group.addSource(source);
    } else {
      group = new BaragonGroup(name, Optional.<String>absent(), Sets.newHashSet(source));
    }
    writeToZk(String.format(LOAD_BALANCER_GROUP_FORMAT, name), group);
    return group;
  }

  public Optional<BaragonGroup> removeSourceFromGroup(String name, String source) {
    Optional<BaragonGroup> maybeGroup = getLoadBalancerGroup(name);
    if (maybeGroup.isPresent()) {
      maybeGroup.get().removeSource(source);
      writeToZk(String.format(LOAD_BALANCER_GROUP_FORMAT, name), maybeGroup.get());
      return maybeGroup;
    } else {
      return Optional.absent();
    }
  }

  public void updateGroupInfo(String name, Optional<String> domain) {
    Optional<BaragonGroup> maybeGroup = getLoadBalancerGroup(name);
    BaragonGroup group;
    if (maybeGroup.isPresent()) {
      group = maybeGroup.get();
      group.setDomain(domain);
    } else {
      group = new BaragonGroup(name, domain, Collections.<String>emptySet());
    }
    writeToZk(String.format(LOAD_BALANCER_GROUP_FORMAT, name), group);
  }

  public Set<String> getLoadBalancerGroupNames() {
    return ImmutableSet.copyOf(getChildren(LOAD_BALANCER_GROUPS_FORMAT));
  }

  public Optional<BaragonAgentMetadata> getAgent(String path) {
    return readFromZk(path, BaragonAgentMetadata.class);
  }

  public Collection<BaragonAgentMetadata> getAgentMetadata(String clusterName) {
    final Collection<String> nodes = getChildren(String.format(LOAD_BALANCER_GROUP_HOSTS_FORMAT, clusterName));

    if (nodes.isEmpty()) {
      return Collections.emptyList();
    }

    final Collection<BaragonAgentMetadata> metadata = Lists.newArrayListWithCapacity(nodes.size());

    for (String node : nodes) {
      try {
        final String value = new String(curatorFramework.getData().forPath(String.format(LOAD_BALANCER_GROUP_HOST_FORMAT, clusterName, node)), Charsets.UTF_8);
        if (value.startsWith("http://")) {
          metadata.add(BaragonAgentMetadata.fromString(value));
        } else {
          metadata.add(objectMapper.readValue(value, BaragonAgentMetadata.class));
        }
      } catch (KeeperException.NoNodeException nne) {
        // uhh, didnt see that...
      } catch (JsonParseException | JsonMappingException je) {
        LOG.warn(String.format("Exception deserializing %s", String.format(LOAD_BALANCER_GROUP_HOST_FORMAT, clusterName, node)), je);
      } catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }

    return metadata;
  }

  public Collection<BaragonAgentMetadata> getAgentMetadata(Collection<String> clusterNames) {
    final Set<BaragonAgentMetadata> metadata = Sets.newHashSet();

    for (String clusterName : clusterNames) {
      metadata.addAll(getAgentMetadata(clusterName));
    }

    return metadata;
  }

  public Optional<String> getBasePathServiceId(String loadBalancerGroup, String basePath) {
    return readFromZk(String.format(LOAD_BALANCER_BASE_PATH_FORMAT, loadBalancerGroup, encodeUrl(basePath)), String.class);
  }

  public void clearBasePath(String loadBalancerGroup, String basePath) {
    deleteNode(String.format(LOAD_BALANCER_BASE_PATH_FORMAT, loadBalancerGroup, encodeUrl(basePath)));
  }

  public void setBasePathServiceId(String loadBalancerGroup, String basePath, String serviceId) {
    writeToZk(String.format(LOAD_BALANCER_BASE_PATH_FORMAT, loadBalancerGroup, encodeUrl(basePath)), serviceId);
  }

  public Collection<String> getBasePaths(String loadBalancerGroup) {
    final Collection<String> encodedPaths = getChildren(String.format(LOAD_BALANCER_BASE_PATHS_FORMAT, loadBalancerGroup));
    final Collection<String> decodedPaths = Lists.newArrayListWithCapacity(encodedPaths.size());

    for (String encodedPath : encodedPaths) {
      decodedPaths.add(decodeUrl(encodedPath));
    }

    return decodedPaths;
  }
}
