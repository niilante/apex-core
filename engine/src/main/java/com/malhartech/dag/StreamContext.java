/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.dag;

import java.util.Collection;
import java.util.HashSet;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;

/**
 * Defines the destination for tuples processed<p>
 * <br>
 *
 * @author Chetan Narsude <chetan@malhar-inc.com>
 */
public class StreamContext implements Context
{
  public static enum State
  {
    UNDEFINED,
    OUTSIDE_WINDOW,
    INSIDE_WINDOW,
    TERMINATED
  }
  private String sourceId;
  private String sinkId;
  private long startingWindowId;
  private HashSet<byte[]> partitions;
  private String id;

  /**
   *
   * @param partitionKeys
   */
  public void setPartitions(Collection<byte[]> partitionKeys)
  {
    if (partitionKeys == null) {
      partitions = null;
    }
    else {
      partitions = new HashSet<byte[]>(partitionKeys.size());
      partitions.addAll(partitionKeys);
    }
  }

  /**
   *
   * @return Collection<byte[]>
   */
  public Collection<byte[]> getPartitions()
  {
    return partitions;
  }

  public StreamContext(String id)
  {
    this.id = id;
  }

  /**
   * @return the startingWindowId
   */
  public long getStartingWindowId()
  {
    return startingWindowId;
  }

  /**
   * @param startingWindowId the startingWindowId to set
   */
  public void setStartingWindowId(long startingWindowId)
  {
    this.startingWindowId = startingWindowId;
  }

  /**
   *
   * @param id
   */
  public void setId(String id)
  {
    this.id = id;
  }

  /**
   *
   * @return String
   */
  public String getId()
  {
    return id;
  }

  /**
   * @return the sourceId
   */
  public String getSourceId()
  {
    return sourceId;
  }

  /**
   * @param upstreamNodeId the sourceId to set
   */
  public void setSourceId(String upstreamNodeId)
  {
    this.sourceId = upstreamNodeId;
  }

  /**
   * @return String (the sink id)
   */
  public String getSinkId()
  {
    return sinkId;
  }

  /**
   * @param downstreamNodeId the sinkId to set this value
   */
  public void setSinkId(String downstreamNodeId)
  {
    this.sinkId = downstreamNodeId;
  }

  @Override
  public String toString()
  {
    return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
            .append("sourceId", sourceId)
            .append("sinkId", sinkId)
            .toString();
  }
}
