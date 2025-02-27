// tslint:disable
/**
 * Yugabyte Cloud
 * YugabyteDB as a Service
 *
 * The version of the OpenAPI document: v1
 * Contact: support@yugabyte.com
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */




/**
 *
 * @export
 * @interface HealthCheckInfo
 */
export interface HealthCheckInfo  {
  /**
   * UUIDs of dead nodes
   * @type {string[]}
   * @memberof HealthCheckInfo
   */
  dead_nodes: string[];
  /**
   *
   * @type {number}
   * @memberof HealthCheckInfo
   */
  most_recent_uptime: number;
  /**
   * UUIDs of under-replicated tablets
   * @type {string[]}
   * @memberof HealthCheckInfo
   */
  under_replicated_tablets: string[];
  /**
   * UUIDs of leaderless tablets
   * @type {string[]}
   * @memberof HealthCheckInfo
   */
  leaderless_tablets: string[];
}
