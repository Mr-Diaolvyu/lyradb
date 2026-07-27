import UIAbility from '@ohos.app.ability.UIAbility';
import window from '@ohos.window';
import type Want from '@ohos.app.ability.Want';
import type AbilityConstant from '@ohos.app.ability.AbilityConstant';

/**
 * 应用入口 Ability。
 *
 * 移动端为 BS 封装客户端：外壳仅承载 ArkWeb 加载远端 BS 前端，
 * 所有数据库连接与查询均由所连服务端完成。
 */
export default class EntryAbility extends UIAbility {
  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        console.error('loadContent 失败: ' + JSON.stringify(err));
      }
    });
  }
}
