package alluxio.master.file.meta;

import javax.annotation.concurrent.ThreadSafe;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import alluxio.resource.LockResource;

/**
 * This class is used for caching the MountTable Entry, which will prevent the scan of MountTable
 * when calling getMountPoint.
 *
 * For example:
 *    We now have three mounted s3 buckets:
 *    (1) s3://a ---- s3://a/1
 *                \-- s3://a/2
 *    (2) s3://b ---- s3://b/3
 *                \-- s3://b/4
 *    (3) s3://c ---- s3://c/5
 *                \-- s3://c/6
 *    Our MountTable looks like this:
 *    <p>
 *      /a      ---> s3://a
 *      /a/b    ---> s3://b
 *      /a/b/c  ---> s3://c
 *    </p>
 *    At the beginning, the MountPointCache is empty.
 *
 *    When we want to know the closest parent directory, which is also the mount point of one of
 *    the three s3 buckets, of the path `/a/b/3`(Usually we do this by calling getMountPoint
 *    method). We can first check if there is any entry in MountPointCache. If there is no valid
 *    entry, we will scan the keys of MountTable(according to the implementation of
 *    getMountPoint), then save the result in MountPointCache by calling add("/a/aab/3", "/a/b").
 *    Now the MountPointCache will be:
 *    <p>
 *      /a/b/3   ---> /a/b
 *    </p>
 *
 *    After calling getMountPoint several times, the MountPointCache will be:
 *    <p>
 *      /a/b/3    --->  /a/b
 *      /a/b/c/6  --->  /a/b/c
 *      /a/b/c/5  --->  /a/b/c
 *      /a/2      --->  /a
 *      /a/b/4    --->  /a/b
 *    </p>
 *    With these key pairs cached, the time complexity of MountPointCache will reduce to O(1),
 *    instead of scanning the whole MountTable everytime.
 *
 *    If we unmount the `s3://b` from Alluxio URI `/a/b`, we will need to also delete the entries
 *    that use `/a/b` as their values.
 *    Now the MountPointCache will be:
 *    <p>
 *      /a/b/c/6  --->  /a/b/c
 *      /a/b/c/5  --->  /a/b/c
 *      /a/2      --->  /a
 *    </p>
 */
@ThreadSafe
public class MountPointCache {
  private final Lock mReadLock;
  private final Lock mWriteLock;

  public static final int DEFAULT_EXACT_MP_CACHE_CAPACITY = 20;
  public static final int DEFAULT_NESTED_MP_CAPACITY = 100;
  public static final int DEFAULT_NON_NESTED_MP_CAPACITY = 100;

  private final Map<String, String> mExactMountPointCache;
  private Set<String> mNestedMountPoints;
  private Set<String> mNonNestedMountPoints;

  /**
   * Creates a new instance of {@link MountPointCache} with default Map capacity.
   */
  public MountPointCache() {
    ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    mReadLock = lock.readLock();
    mWriteLock = lock.writeLock();
    mExactMountPointCache = new HashMap<>(DEFAULT_EXACT_MP_CACHE_CAPACITY);
    mNestedMountPoints = new HashSet<>(DEFAULT_NESTED_MP_CAPACITY);
    mNonNestedMountPoints = new HashSet<>(DEFAULT_NON_NESTED_MP_CAPACITY);

  }

  /**
   * Create a new instance of {@link MountPointCache}.
   * @param capacity the capacity of the map from Alluxio URI to its closest parent Alluxio URI,
   * which is a MountPoint that is saved in MountTable
   */
  public MountPointCache(int capacity) {
    ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    mReadLock = lock.readLock();
    mWriteLock = lock.writeLock();
    mExactMountPointCache = new HashMap<>(capacity);
    mNestedMountPoints = new HashSet<>(DEFAULT_NESTED_MP_CAPACITY);
    mNonNestedMountPoints = new HashSet<>(DEFAULT_NON_NESTED_MP_CAPACITY);
  }


  public void add(String path, boolean isNested) {
    try (LockResource w = new LockResource(mWriteLock)) {
      if (isNested) {
        mNestedMountPoints.add(path);
      } else {
        mNonNestedMountPoints.add(path);
      }
    }
  }
  /**
   * Add an entry into cache.
   * This method should be called when getMountPoint calculates the parent directory of a given
   * directory.
   * @param path a subdirectory of parentMountPointPath
   * @param parentMountPointPath a directory which is the closest parent directory of path, and
   * is also a mount point of a UFS
   */
  public void add(String path, String parentMountPointPath) {
    try (LockResource w = new LockResource(mWriteLock)) {

    }
  }

  /**
   * Get the parentMountPointPath by path
   * @param path the subdirectory of the expected mount point directory
   * @return the closest parent directory of path, which is also a mount point of a UFS. If it
   * doesn't exist in the cache, return null.
   */
  public String get(String path) {
    String res;
    try (LockResource w = new LockResource(mReadLock)) {
      res = mExactMountPointCache.get(path);
    }
    return res;
  }

  /**
   * Remove all the pairs which its value equals to the given parentMountPointPath.
   * This method should be called when the parentMountPointPath is unmounted.
   * @param parentMountPointPath the parent directory to be removed from the cache.
   */
  public void delete(String parentMountPointPath) {
    try (LockResource w = new LockResource(mWriteLock)) {
      mExactMountPointCache.entrySet().removeIf(entry -> entry.getValue().equals(parentMountPointPath));
    }
  }

  /**
   * Clear the whole cache
   */
  public void clear() {
    try (LockResource w = new LockResource(mWriteLock)) {
      mExactMountPointCache.clear();
    }
  }
}
