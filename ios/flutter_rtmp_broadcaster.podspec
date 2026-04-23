#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_rtmp_broadcaster.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_rtmp_broadcaster'
  s.version          = '0.1.0'
  s.summary          = 'Flutter plugin for RTMP broadcasting with native overlay compositing.'
  s.description      = <<-DESC
Flutter plugin that owns the device camera, composites sponsor images and a live
scoreband PNG onto video frames natively, and broadcasts via RTMP.
                       DESC
  s.homepage         = 'https://github.com/flutterrtmp/broadcaster'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'flutter_rtmp_broadcaster' => 'shoeb.adnan@adnanfoundation.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.dependency 'HaishinKit', '~> 2.2'
  s.ios.deployment_target = '14.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  # If your plugin requires a privacy manifest, for example if it uses any
  # required reason APIs, update the PrivacyInfo.xcprivacy file to describe your
  # plugin's privacy impact, and then uncomment this line. For more information,
  # see https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  # s.resource_bundles = {'flutter_rtmp_broadcaster_privacy' => ['Resources/PrivacyInfo.xcprivacy']}
end
