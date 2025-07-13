git submodule init
git submodule update
cd arc42-template
git checkout master
git pull
cd ..
./gradlew createTemplatesFromGoldenMaster
./gradlew arc42
./gradlew createDistribution
echo "please check the results in arc42-template/dist"
echo "and if ok, add, commit and push it"
