/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.grpc;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * A provider of {@link DataMessageMarshaller} for a gRPC call.
 *
 * @param <ReqT> type of the request message
 * @param <ResT> type of the response message
 */
public class DataMessageMarshallerProvider<ReqT, ResT>  {
  private final DataMessageMarshaller<ReqT> mRequestMarshaller;
  private final DataMessageMarshaller<ResT> mResponseMarshaller;

  /**
   * @param requestMarshaller the marshaller for the request, or null if not provided
   * @param responseMarshaller the marshaller for the response, or null if not provided
   */
  public DataMessageMarshallerProvider(@Nullable DataMessageMarshaller<ReqT> requestMarshaller,
      @Nullable DataMessageMarshaller<ResT> responseMarshaller) {
    mRequestMarshaller = requestMarshaller;
    mResponseMarshaller = responseMarshaller;
  }

  /**
   * @return the request marshaller
   */
  public Optional<DataMessageMarshaller<ReqT>> getRequestMarshaller() {
    return Optional.ofNullable(mRequestMarshaller);
  }

  /**
   * @return the response marshaller
   */
  public Optional<DataMessageMarshaller<ResT>> getResponseMarshaller() {
    return Optional.ofNullable(mResponseMarshaller);
  }
}
