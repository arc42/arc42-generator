sed -i 's/arc42-template/req42-framework/g' build.gradle
sed -i 's/arc42-template/req42-framework/g' buildconfig.groovy
sed -i 's/arc42-template/req42-framework/g' settings.gradle
sed -i 's/arc42-template/req42-framework/g' subBuild.gradle
rm -r build
cd req42-framework
git checkout main
git pull
cd ..
./gradlew createTemplatesFromGoldenMaster
./gradlew arc42
./gradlew createDistribution
echo "please check the results in req42-framework/dist"
echo "and if ok, add, commit and push it"
