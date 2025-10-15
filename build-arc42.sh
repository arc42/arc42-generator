echo "Building arc42 template"
echo "install pandoc"
wget https://github.com/jgm/pandoc/releases/download/3.7.0.2/pandoc-3.7.0.2-1-amd64.deb
sudo dpkg -i pandoc-3.7.0.2-1-amd64.deb
echo "init and updatesubmodules"
git submodule init
git submodule update
cd arc42-template
git checkout master
git pull
cd ..
echo "build arc42 template"
./gradlew createTemplatesFromGoldenMaster
echo "convert to other singlepage formats"
./gradlew arc42
echo "generate docbook multipage format"
./gradlew generateDocbookMP
echo "convert docbook multipage to other multipage formats"
./gradlew arc42MP
echo "create distributions"
./gradlew createDistribution
echo "please check the results in arc42-template/dist"
echo "and if ok, add, commit and push it"
