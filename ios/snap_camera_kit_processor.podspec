#
# Podspec for the snap_camera_kit_processor Flutter plugin.
#
# Phase 1: scaffold only. Snap Camera Kit iOS SDK pod is commented out
# and will be enabled in Phase 3 when access is granted. The rest of
# the pod is wired so the plugin builds for iOS and Phase 2 LiveKit
# integration can be exercised.
#
# Distribution: Snap Camera Kit iOS SDK ships on CocoaPods under the
# pod name `SCSDKCameraKit`. Verified via the public sample app:
#   https://github.com/Snapchat/camera-kit-ios-sdk
#
Pod::Spec.new do |s|
  s.name             = 'snap_camera_kit_processor'
  s.version          = '0.0.1'
  s.summary          = 'Snap Camera Kit AR pipeline wired into LiveKit for vibezo.'
  s.homepage         = 'https://github.com/vibezo'
  s.license          = { :type => 'Proprietary', :text => 'Proprietary - Vibezo' }
  s.author           = { 'Vibezo' => 'CEO@vibezo.net' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'

  # Snap Camera Kit iOS SDK. The CocoaPods name is `SCCameraKit` (the
  # Swift import module is `SCSDKCameraKit`). Pinned to match the Android
  # Maven coordinate's release train.
  # 1.50.0: latest on CocoaPods — picks up lenses built with newer Lens
  # Studio (their higher mobile min-SDK was filtered out on 1.49.0).
  s.dependency 'SCCameraKit', '1.50.0'

  # Frame interception goes through flutter_webrtc — same pattern as the
  # DeepAR plugin. The host app's flutter_webrtc pod provides the
  # LocalVideoTrack class we attach our frame processor to.
  s.dependency 'flutter_webrtc'

  s.ios.deployment_target = '15.0'
  s.swift_version = '5.0'
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
end
